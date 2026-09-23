package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer;
import com.sunm2n.pay.payment.application.confirmation.RetryMetrics;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * S2-a 낙관적 락 (SCENARIO 142행).
 *
 * <p>재현과 같은 게이트를 쓴다. 잠금 없는 읽기라 워커 4개가 모두 잔액을 읽는 시점까지 도달하고, 판정은 커밋 시점의 버전 비교가 한다. 탈락한 트랜잭션은 <b>전체
 * 롤백</b>되므로 {@code IN_PROGRESS} 전이도 취소되고, 재시도는 다시 {@code READY} 를 보고 CAS 를 통과한다.
 *
 * <p>이 테스트가 보는 범위는 <b>1차 시도 충돌 → 전체 롤백 → 재시도 성공</b>과 최종 상태다. 게이트는 스레드당 한 번만 막으므로 2차 시도부터는 워커들이 흩어지고
 * 충돌 횟수가 결정적이지 않다. 최대 시도·상한 초과·지연은 {@link
 * com.sunm2n.pay.payment.application.confirmation.RetryPolicyTest} 가 통제된 delegate 로 확인한다.
 *
 * <p>충돌 <b>하한</b>은 결정적이다. 1차 시도에서 넷이 같은 버전을 읽으므로 커밋에 성공하는 것은 하나뿐이고, 나머지 3건은 반드시 충돌한다.
 */
class OptimisticLockConcurrencyTest extends AbstractIntegrationTest {

  private static final int WORKERS = 4;
  private static final String ORDER_ID = "order-s2a";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 10_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("retryingOptimisticDebitConfirmer")
  private PaymentConfirmer retryingOptimisticConfirmer;

  @Autowired
  @Qualifier("optimisticRetryMetrics")
  private RetryMetrics retryMetrics;

  private Long merchantId;

  @BeforeEach
  void resolveMerchantAndResetMetrics() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    retryMetrics.reset();
  }

  @Test
  @DisplayName("충돌한 요청은 전체 롤백 뒤 재시도해 성공하고, 잔액이 모자란 한 건만 거절된다")
  void conflictsAreRolledBackAndRetriedUntilTheBalanceRunsOut() {
    // 낙관적 실험 중 충전은 사전 시딩만 한다. version 을 올리지 않는 naive 충전이 섞이면 보호가 성립하지 않는다.
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> paymentKeys = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    concurrencyGate.arm(ConcurrencyGate.WALLET_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () ->
                retryingOptimisticConfirmer.confirm(
                    merchantId, paymentKeys.poll(), ORDER_ID, AMOUNT));

    assertThat(results.successCount()).as("10,000 으로 3,000 짜리는 세 건까지다").isEqualTo(3);
    assertThat(results.failuresOf(InsufficientBalanceException.class))
        .as("남은 한 건은 재시도 끝에 잔액 부족으로 종료된다")
        .hasSize(1);
    assertThat(results.failuresOtherThan(InsufficientBalanceException.class))
        .as("예상 밖 오류는 없다 - 낙관적 충돌은 재시도가 흡수한다")
        .isEmpty();

    assertThat(retryMetrics.conflicts())
        .as("1차 시도에서 넷이 같은 버전을 읽으므로 충돌 3건은 보장된다")
        .isGreaterThanOrEqualTo(WORKERS - 1);
    assertThat(retryMetrics.retries()).isGreaterThanOrEqualTo(WORKERS - 1);
    assertThat(retryMetrics.exhausted())
        .as("버전을 올리는 것은 성공한 차감 3건뿐이라 4번째 시도에서는 반드시 판정이 난다")
        .isZero();

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - 3 * AMOUNT);
    assertThat(versionOf(Seeds.MEMBER_ID_1)).as("커밋에 성공한 차감만 버전을 올린다").isEqualTo(3);
    assertThat(payLedgerCount(Seeds.MEMBER_ID_1)).as("롤백된 시도의 원장은 남지 않는다").isEqualTo(3);
    assertApprovedWithTimestamp(3);
    assertOneLedgerRowPerApprovedPayment();
    assertRejectedLeftNothingBehind();

    invariants.assertAll();
  }

  private List<String> createPayments(int count) {
    List<String> paymentKeys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      paymentKeys.add(
          paymentService
              .create(merchantId, ORDER_ID, AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
              .getPaymentKey());
    }
    return paymentKeys;
  }

  private void assertApprovedWithTimestamp(int expected) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'DONE' AND approved_at IS NOT NULL",
                Integer.class))
        .as("성공한 결제는 DONE + approved_at 이다")
        .isEqualTo(expected);
  }

  private void assertOneLedgerRowPerApprovedPayment() {
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT p.payment_key, COUNT(l.id) AS pay_rows FROM payment p"
                    + " LEFT JOIN wallet_ledger l ON l.payment_id = p.id AND l.type = 'PAY'"
                    + " WHERE p.status = 'DONE'"
                    + " GROUP BY p.payment_key HAVING COUNT(l.id) <> 1"))
        .as("재시도가 중복 원장을 만들지 않는다 - 결제당 PAY 정확히 1건")
        .isEmpty();
  }

  private void assertRejectedLeftNothingBehind() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'READY'", Integer.class))
        .as("거절된 결제는 IN_PROGRESS 가 아니라 READY 로 롤백된다")
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger l JOIN payment p ON p.id = l.payment_id"
                    + " WHERE p.status <> 'DONE'",
                Integer.class))
        .isZero();
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private long versionOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT version FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private int payLedgerCount(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id"
            + " WHERE w.member_id = ? AND l.type = 'PAY'",
        Integer.class,
        memberId);
  }
}
