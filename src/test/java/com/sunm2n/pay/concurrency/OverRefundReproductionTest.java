package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.cancellation.PaymentCanceller;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.PaymentStatus;
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
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * S3 재현 — 취소가 잔여 금액과 지갑 잔액을 덮어쓴다.
 *
 * <p><b>전제는 "S1·S2 적용"</b>이다. 승인은 본선(CAS + 비관적 락 차감)이고, 취소만 {@code naivePaymentCanceller} 로 Phase 0
 * 그대로 둔다.
 *
 * <p>재현 1 은 <b>같은 결제</b>의 부분취소 경쟁이다. 두 요청이 같은 {@code balance_amount} 를 읽고 검사를 통과해, flush 시점의 {@code
 * UPDATE payment SET balance_amount = <절대값>} 이 서로를 덮어쓴다. S2 와 같은 Lost Update 이고 대상만 결제다.
 *
 * <p>재현 2 는 <b>서로 다른 결제가 같은 지갑</b>을 쓰는 경쟁이다. 같은 결제를 쓰면 payment 행에서 먼저 직렬화되어 지갑 경쟁이 가려진다. 승인이 지갑을
 * {@code FOR UPDATE} 로 잠가도, 환불이 잠그지 않고 읽으면 옛 잔액으로 계산한 값을 덮어써 승인의 차감이 사라진다. 락 규칙은 유스케이스가 아니라 행 단위로
 * 지켜져야 한다는 것이 이 재현의 요점이다.
 *
 * <p><b>이 테스트는 결함이 재현될 때 green 이다.</b> 본선이 바뀐 뒤에도 naive 취소를 고른 이 빈은 계속 이 결과를 낸다.
 */
class OverRefundReproductionTest extends AbstractIntegrationTest {

  private static final long AMOUNT = 10_000L;
  private static final long PARTIAL_CANCEL = 7_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("naivePaymentCanceller")
  private PaymentCanceller naivePaymentCanceller;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("재현 1 - 10,000 결제에 7,000 부분취소 2건이 모두 성공해 취소 합계가 14,000 이 된다")
  void concurrentPartialCancelsOverRefund() {
    String paymentKey = confirmed("order-s3-1", AMOUNT, PaymentMethod.CARD);
    int cancelsBefore = fakeCardApprovalClient.getCancelCount();

    // 둘 다 잔여 금액 10,000 을 읽은 시점을 맞춘다.
    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2, () -> naivePaymentCanceller.cancel(merchantId, paymentKey, PARTIAL_CANCEL, "경쟁"));

    assertThat(results.failures()).as("둘 다 10,000 을 보고 검사했으므로 아무도 거절되지 않는다").isEmpty();
    assertThat(results.successCount()).isEqualTo(2);

    assertThat(cancelSumOf(paymentKey)).as("취소 이력은 둘 다 남는다").isEqualTo(2 * PARTIAL_CANCEL);
    assertThat(balanceAmountOf(paymentKey))
        .as("두 트랜잭션이 모두 3,000 이라는 같은 절대값을 저장했다")
        .isEqualTo(AMOUNT - PARTIAL_CANCEL);
    assertThat(statusOf(paymentKey)).isEqualTo(PaymentStatus.PARTIAL_CANCELED.name());
    assertThat(fakeCardApprovalClient.getCancelCount() - cancelsBefore)
        .as("카드사에는 14,000 이 환불되었다 - 실제 피해는 여기다")
        .isEqualTo(2);

    assertThatThrownBy(() -> invariants.assertPaymentBalanceAmountMatchesCancels())
        .as("3,000 != 10,000 - 14,000")
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> invariants.assertCancelSumWithinApprovedAmount())
        .as("14,000 > 10,000")
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("재현 2 - 잠그지 않는 환불이 같은 지갑의 비관적 락 차감을 덮어쓴다")
  void unlockedRefundOverwritesLockedDebit() {
    walletService.charge(Seeds.MEMBER_ID_1, 10_000L);
    String refunded = confirmed("order-s3-2a", 5_000L, PaymentMethod.MONEY);
    String debited = created("order-s3-2b", 3_000L, PaymentMethod.MONEY);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(5_000L);
    AtomicInteger turn = new AtomicInteger();

    // 환불 워커를 잔액 5,000 을 읽은 직후에 붙잡는다. 그 시점에 환불이 보유한 락은 방금 INSERT 한
    // payment_cancel 행뿐이라 승인과 겹치지 않는다 (docs/plan/S3.md 6절).
    concurrencyGate.armHold(ConcurrencyGate.WALLET_READ);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) {
                return naivePaymentCanceller.cancel(merchantId, refunded, 2_000L, "환불");
              }
              concurrencyGate.awaitArrival(ConcurrencyGate.WALLET_READ);
              try {
                return paymentService.confirm(merchantId, debited, "order-s3-2b", 3_000L);
              } finally {
                // 승인이 실패해도 붙잡힌 환불을 풀어 상한까지 기다리지 않게 한다
                concurrencyGate.release(ConcurrencyGate.WALLET_READ);
              }
            });

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(2);
    assertThat(statusOf(debited)).as("승인은 정상 커밋되었다").isEqualTo(PaymentStatus.DONE.name());

    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1))
        .as("원장이 말하는 잔액: 10,000 - 5,000 - 3,000 + 2,000")
        .isEqualTo(4_000L);
    assertThat(balanceOf(Seeds.MEMBER_ID_1))
        .as("환불이 승인 전에 읽은 5,000 에 2,000 을 더해 덮어썼다 - 승인의 차감 3,000 이 사라졌다")
        .isEqualTo(7_000L);

    assertThatThrownBy(() -> invariants.assertWalletBalanceMatchesLedger())
        .isInstanceOf(AssertionError.class);
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

  private long cancelSumOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(c.cancel_amount), 0) FROM payment_cancel c"
            + " JOIN payment p ON p.id = c.payment_id WHERE p.payment_key = ?",
        Long.class,
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
}
