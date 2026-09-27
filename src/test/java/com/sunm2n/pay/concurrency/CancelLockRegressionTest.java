package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.PaymentStatus;
import com.sunm2n.pay.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S3 회귀 — 본선(결제 {@code FOR UPDATE} + 비관적 락 환불)에서 같은 경쟁이 어떻게 끝나는가.
 *
 * <p>{@link PaymentService} 의 기본 빈을 그대로 호출한다. 회귀는 <b>프로덕션이 실제로 타는 경로</b>를 확인해야 한다.
 *
 * <p>같은 결제의 취소끼리는 {@code PAYMENT_LOCK_ATTEMPT} 로 맞춘다. 결제 락을 잡기 <b>전</b>이라 참가자가 보유한 락이 없다 — 선행 소유
 * 조회는 잠그지 않는다. 잠그며 읽는 조회의 반환 직후에는 게이트를 걸 수 없다 (plan/S2.md 4.1).
 *
 * <p>취소와 승인이 같은 지갑을 다투는 회귀 3 은 {@code WALLET_LOCK_ATTEMPT} 로 맞춘다. 그 시점에 취소 워커는 P1, 승인 워커는 CAS 로 P2
 * 의 X 락을 들고 있다. 서로 다른 행이라 막지 않는다 — plan/S2.md 4.2 가 "S3 에서 재사용할 때 다시 점검하라" 고 남긴 항목이다.
 */
class CancelLockRegressionTest extends AbstractIntegrationTest {

  private static final long AMOUNT = 10_000L;
  private static final long PARTIAL_CANCEL = 7_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("회귀 1 - CARD 7,000 부분취소 2건은 1건만 성공하고 나머지는 잔여 3,000 을 보고 거절된다")
  void concurrentPartialCancelsAreSerialized() {
    String paymentKey = confirmed("order-s3r-1", AMOUNT, PaymentMethod.CARD);
    int cancelsBefore = fakeCardApprovalClient.getCancelCount();

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_LOCK_ATTEMPT, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2, () -> paymentService.cancel(merchantId, paymentKey, PARTIAL_CANCEL, "경쟁"));

    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failuresOf(CancelAmountExceededException.class))
        .as("잠근 뒤 읽은 현재 값이라 예외의 잔여 금액도 사실이다")
        .singleElement()
        .satisfies(e -> assertThat(e.getMessage()).contains("balanceAmount=3000"));
    assertThat(results.failuresOtherThan(CancelAmountExceededException.class)).isEmpty();

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(balanceAmountOf(paymentKey)).isEqualTo(AMOUNT - PARTIAL_CANCEL);
    assertThat(statusOf(paymentKey)).isEqualTo(PaymentStatus.PARTIAL_CANCELED.name());
    assertThat(fakeCardApprovalClient.getCancelCount() - cancelsBefore)
        .as("탈락자는 카드사를 부르지 않는다")
        .isEqualTo(1);

    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 2 - MONEY 7,000 부분취소 2건은 1건만 환불되고 잔액이 원장 합계와 같다")
  void concurrentMoneyCancelsRefundOnce() {
    walletService.charge(Seeds.MEMBER_ID_1, AMOUNT);
    String paymentKey = confirmed("order-s3r-2", AMOUNT, PaymentMethod.MONEY);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isZero();

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_LOCK_ATTEMPT, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2, () -> paymentService.cancel(merchantId, paymentKey, PARTIAL_CANCEL, "경쟁"));

    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failuresOf(CancelAmountExceededException.class)).hasSize(1);
    assertThat(results.failuresOtherThan(CancelAmountExceededException.class)).isEmpty();

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(refundLedgerCountOf(Seeds.MEMBER_ID_1)).as("탈락자는 환불 원장을 남기지 않는다").isEqualTo(1);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(PARTIAL_CANCEL);
    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1)).isEqualTo(PARTIAL_CANCEL);

    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 3 - 서로 다른 결제의 환불과 차감이 같은 지갑을 다퉈도 둘 다 반영된다")
  void refundAndDebitOnTheSameWalletBothSurvive() {
    walletService.charge(Seeds.MEMBER_ID_1, 10_000L);
    String refunded = confirmed("order-s3r-3a", 5_000L, PaymentMethod.MONEY);
    String debited = created("order-s3r-3b", 3_000L, PaymentMethod.MONEY);
    AtomicInteger turn = new AtomicInteger();

    // 재현 2 와 같은 구도다. 둘 다 지갑을 잠그며 읽으므로 반환 직후가 아니라 호출 직전에 맞춘다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_LOCK_ATTEMPT, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2,
            () ->
                turn.getAndIncrement() == 0
                    ? paymentService.cancel(merchantId, refunded, 2_000L, "환불")
                    : paymentService.confirm(merchantId, debited, "order-s3r-3b", 3_000L));

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(2);
    assertThat(statusOf(debited)).isEqualTo(PaymentStatus.DONE.name());
    assertThat(statusOf(refunded)).isEqualTo(PaymentStatus.PARTIAL_CANCELED.name());

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).as("재현에서는 7,000 이었다").isEqualTo(4_000L);
    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1)).isEqualTo(4_000L);

    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 4 - 전액취소와 부분취소가 겹치면 탈락자의 예외는 승자에 따라 정해진다 (상태 -> 금액 순)")
  void loserExceptionFollowsTheWinner() {
    String paymentKey = confirmed("order-s3r-4", AMOUNT, PaymentMethod.CARD);
    AtomicInteger turn = new AtomicInteger();

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_LOCK_ATTEMPT, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2,
            () ->
                paymentService.cancel(
                    merchantId,
                    paymentKey,
                    turn.getAndIncrement() == 0 ? AMOUNT : PARTIAL_CANCEL,
                    "경쟁"));

    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failureCount()).isEqualTo(1);
    Payment winner = results.successes().get(0);
    Throwable loser = results.failures().get(0);

    if (winner.getStatus() == PaymentStatus.CANCELED) {
      assertThat(loser)
          .as("전액취소가 이기면 탈락자는 CANCELED 를 읽는다 - 금액보다 상태를 먼저 검사한다")
          .isInstanceOf(InvalidPaymentStatusException.class);
      assertThat(balanceAmountOf(paymentKey)).isZero();
    } else {
      assertThat(winner.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
      assertThat(loser)
          .as("부분취소가 이기면 탈락자는 잔여 3,000 에 10,000 을 요청한 것이다")
          .isInstanceOf(CancelAmountExceededException.class);
      assertThat(balanceAmountOf(paymentKey)).isEqualTo(AMOUNT - PARTIAL_CANCEL);
    }
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);

    invariants.assertAll();
  }

  private String created(String orderId, long amount, PaymentMethod method) {
    Long memberId = method == PaymentMethod.MONEY ? Seeds.MEMBER_ID_1 : null;
    return paymentService.create(merchantId, orderId, amount, method, memberId).getPaymentKey();
  }

  private String confirmed(String orderId, long amount, PaymentMethod method) {
    String paymentKey = created(orderId, amount, method);
    paymentService.confirm(merchantId, paymentKey, orderId, amount);
    return paymentKey;
  }

  private int cancelRowsOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }

  private long balanceAmountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT balance_amount FROM payment WHERE payment_key = ?", Long.class, paymentKey);
  }

  private String statusOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM payment WHERE payment_key = ?", String.class, paymentKey);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private long ledgerSumOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(l.amount), 0) FROM wallet_ledger l"
            + " JOIN wallet w ON w.id = l.wallet_id WHERE w.member_id = ?",
        Long.class,
        memberId);
  }

  private int refundLedgerCountOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id"
            + " WHERE w.member_id = ? AND l.type = 'REFUND'",
        Integer.class,
        memberId);
  }
}
