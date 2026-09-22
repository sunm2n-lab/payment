package com.sunm2n.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.PaymentMethod;
import com.sunm2n.payment.domain.exception.InsufficientBalanceException;
import com.sunm2n.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.payment.support.AbstractIntegrationTest;
import com.sunm2n.payment.support.ConcurrencyGate;
import com.sunm2n.payment.support.ConcurrentRunner;
import com.sunm2n.payment.support.Seeds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S1 회귀 — 조건부 UPDATE 를 넣은 뒤 같은 경쟁에서 무엇이 달라지는가.
 *
 * <p>재현 때와 같은 게이트(payment 조회 직후)를 그대로 쓴다. 잠금 없는 읽기라 개선된 구현에서도 워커 5개가 모두 게이트에 도달하고, 그 다음 CAS 에서 하나만
 * 통과한다. 게이트를 CAS 뒤에 두면 참가자가 하나뿐이라 테스트가 멈춘다 (SCENARIO 95행).
 *
 * <p>MONEY 의 지갑 게이트는 재현 전용이라 여기서는 무장하지 않는다. 개선된 구현에서 탈락한 요청은 지갑을 읽지 않으므로 참가자가 모이지 않는다.
 */
class DuplicateConfirmRegressionTest extends AbstractIntegrationTest {

  private static final int WORKERS = 5;
  private static final String ORDER_ID = "order-s1";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 10_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;
  @Autowired private PlatformTransactionManager transactionManager;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("CARD - 동시 승인 5건 중 1건만 성공하고 카드사는 한 번만 호출된다")
  void cardIsApprovedExactlyOnce() {
    String paymentKey = createPayment(PaymentMethod.CARD, null);

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results = confirmConcurrently(paymentKey);

    assertSingleWinner(results);
    assertThat(fakeCardApprovalClient.getApproveCount()).as("외부 승인은 결제 1건에 한 번뿐이다").isEqualTo(1);
    assertThat(currentStatus(paymentKey)).isEqualTo("DONE");
    invariants.assertAll();
  }

  @Test
  @DisplayName("MONEY - 동시 승인 5건 중 1건만 성공하고 PAY 원장도 한 건이라 불변식이 유지된다")
  void moneyIsDeductedExactlyOnce() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String paymentKey = createPayment(PaymentMethod.MONEY, Seeds.MEMBER_ID_1);

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results = confirmConcurrently(paymentKey);

    assertSingleWinner(results);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - AMOUNT);
    assertThat(payLedgerCount(paymentKey)).as("PAY 원장은 한 건이다").isEqualTo(1);
    assertThat(currentStatus(paymentKey)).isEqualTo("DONE");
    invariants.assertAll();
  }

  @Test
  @DisplayName("승인 전체가 한 트랜잭션이라 잔액이 부족하면 IN_PROGRESS 전이까지 함께 롤백된다")
  void failedApprovalRollsBackTheStateTransition() {
    walletService.charge(Seeds.MEMBER_ID_1, AMOUNT - 1);
    String paymentKey = createPayment(PaymentMethod.MONEY, Seeds.MEMBER_ID_1);

    assertThatThrownBy(() -> paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT))
        .isInstanceOf(InsufficientBalanceException.class);

    assertThat(currentStatus(paymentKey))
        .as("커밋된 IN_PROGRESS 는 남지 않는다. 중간 상태를 커밋하는 것은 S8 의 주제다")
        .isEqualTo("READY");
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(AMOUNT - 1);
    assertThat(payLedgerCount(paymentKey)).isZero();
    invariants.assertAll();
  }

  @Test
  @DisplayName("탈락 판정은 갱신 건수 0 으로만 내린다 - RR 에서 재조회는 여전히 READY 를 돌려준다")
  void loserCannotLearnTheWinnerStatusByReading() {
    String paymentKey = createPayment(PaymentMethod.CARD, null);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    transactionTemplate.executeWithoutResult(
        tx -> {
          // 1. 첫 조회가 이 트랜잭션의 스냅샷을 확정한다.
          assertThat(currentStatus(paymentKey)).isEqualTo("READY");

          // 2. 그 사이 다른 트랜잭션이 승인하고 커밋한다.
          ConcurrentRunner.Results<Payment> winner =
              ConcurrentRunner.run(
                  1, () -> paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT));
          assertThat(winner.failures()).isEmpty();

          // 3. 조건부 UPDATE 는 최신 커밋 값을 다시 평가하므로 0 건이다.
          int updated =
              jdbcTemplate.update(
                  "UPDATE payment SET status = 'IN_PROGRESS'"
                      + " WHERE payment_key = ? AND status = 'READY'",
                  paymentKey);
          assertThat(updated).as("탈락을 확정하는 유일한 근거").isZero();

          // 4. 그런데 평범한 재조회는 여전히 READY 다. 이 값으로 응답을 만들면 거짓말이 된다.
          assertThat(currentStatus(paymentKey))
              .as("REPEATABLE READ 스냅샷은 1 번 시점에 고정돼 있다")
              .isEqualTo("READY");
        });

    assertThat(currentStatus(paymentKey)).as("트랜잭션 밖에서는 승자의 결과가 보인다").isEqualTo("DONE");
  }

  private void assertSingleWinner(ConcurrentRunner.Results<Payment> results) {
    assertThat(results.successCount()).as("승인에 성공한 요청은 하나뿐이다").isEqualTo(1);
    assertThat(results.failuresOf(InvalidPaymentStatusException.class))
        .as("나머지는 정해진 상태 거절을 받는다")
        .hasSize(WORKERS - 1);
    assertThat(results.failuresOtherThan(InvalidPaymentStatusException.class))
        .as("예상 밖 오류는 없다")
        .isEmpty();
  }

  private String createPayment(PaymentMethod method, Long memberId) {
    return paymentService.create(merchantId, ORDER_ID, AMOUNT, method, memberId).getPaymentKey();
  }

  private ConcurrentRunner.Results<Payment> confirmConcurrently(String paymentKey) {
    return ConcurrentRunner.run(
        WORKERS, () -> paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT));
  }

  private String currentStatus(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM payment WHERE payment_key = ?", String.class, paymentKey);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private int payLedgerCount(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger l JOIN payment p ON p.id = l.payment_id"
            + " WHERE p.payment_key = ? AND l.type = 'PAY'",
        Integer.class,
        paymentKey);
  }
}
