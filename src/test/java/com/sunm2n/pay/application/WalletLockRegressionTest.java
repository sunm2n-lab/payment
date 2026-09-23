package com.sunm2n.pay.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.domain.Payment;
import com.sunm2n.pay.domain.PaymentMethod;
import com.sunm2n.pay.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S2-b 회귀 — 본선(CAS 승인 + 비관적 락)에서 같은 경쟁이 어떻게 끝나는가.
 *
 * <p>{@link PaymentService} / {@link WalletService} 의 기본 빈을 그대로 호출한다. 재현 테스트가 빈을 골라 쓰는 것과 달리, 회귀는
 * <b>프로덕션이 실제로 타는 경로</b>를 확인해야 한다.
 *
 * <p>게이트 선택에 규칙이 있다. {@code FOR UPDATE} 조회의 <b>반환 직후</b>에는 게이트를 걸 수 없다 — 먼저 락을 잡은 워커가 대기하는 동안 나머지는
 * 락에 막혀 게이트에 도달하지 못해 테스트가 멈춘다. 그래서 승인끼리의 경쟁은 결제 조회 시점({@code PAYMENT_READ})으로 맞추고, 충전과 승인의 경쟁은 두
 * 경로가 락을 잡기 <b>전에</b> 만나는 유일한 공통 지점({@code WALLET_LOCK_ATTEMPT})으로 맞춘다.
 *
 * <p>{@code WALLET_LOCK_ATTEMPT} 가 보장하는 것은 <b>참가자 누구도 아직 wallet 락을 잡지 않았다</b>는 것뿐이다. 승인 워커는 그 시점에
 * 이미 S1 의 CAS 로 payment 의 X 락을 들고 있다. 이 테스트에서는 충전 워커가 payment 를 건드리지 않고 승인 워커도 하나라 서로 막지 않지만, S3·S4
 * 에서 재사용할 때는 그 시점까지 획득한 락을 다시 점검해야 한다. 이 게이트는 DB 락 <b>대기</b>가 일어났다는 증거도 아니다 — 동시 출발만 보장한다.
 */
class WalletLockRegressionTest extends AbstractIntegrationTest {

  private static final int WORKERS = 4;
  private static final int SAME_PAYMENT_WORKERS = 5;
  private static final String ORDER_ID = "order-s2b";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 10_000L;
  private static final long CHARGE_AMOUNT = 5_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("회귀 1 - 서로 다른 결제 4건이 한 지갑을 다투면 성공 3 / 부족 1 이고 불변식이 유지된다")
  void concurrentDebitsOnTheSameWalletAreSerialized() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> paymentKeys = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> paymentService.confirm(merchantId, paymentKeys.poll(), ORDER_ID, AMOUNT));

    assertThat(results.successCount()).isEqualTo(3);
    assertThat(results.failuresOf(InsufficientBalanceException.class))
        .as("네 번째는 앞선 세 건이 커밋한 잔액을 보고 검사한다")
        .hasSize(1);
    assertThat(results.failuresOtherThan(InsufficientBalanceException.class))
        .as("본선에서 나오는 실패는 잔액 부족뿐이다")
        .isEmpty();

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - 3 * AMOUNT);
    assertThat(payLedgerCount(Seeds.MEMBER_ID_1)).isEqualTo(3);
    assertApprovedWithTimestamp(3);
    assertOneLedgerRowPerApprovedPayment();
    assertRejectedLeftNothingBehind(1);

    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 2 - 충전과 승인이 동시에 와도 둘 다 반영되어 잔액이 원장 합계와 같다")
  void concurrentChargeAndDebitBothSurvive() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String paymentKey = createPayments(1).get(0);
    AtomicInteger turn = new AtomicInteger();

    // 충전도 승인도 잠그며 읽는다. 반환 직후에 걸면 먼저 잠근 쪽이 대기하는 동안 나머지가 락에 막혀 영영 멈춘다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_LOCK_ATTEMPT, 2);
    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            2,
            () ->
                turn.getAndIncrement() == 0
                    ? walletService.charge(Seeds.MEMBER_ID_1, CHARGE_AMOUNT)
                    : paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT));

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(2);

    long expected = INITIAL_BALANCE + CHARGE_AMOUNT - AMOUNT;
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).as("재현에서는 나오지 않던 값이다").isEqualTo(expected);
    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1)).isEqualTo(expected);
    assertApprovedWithTimestamp(1);
    assertOneLedgerRowPerApprovedPayment();

    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 3 - 같은 결제 5건 동시 승인은 여전히 1건만 성공한다 (S1 회귀)")
  void duplicateConfirmStillLosesAllButOne() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String paymentKey = createPayments(1).get(0);

    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, SAME_PAYMENT_WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            SAME_PAYMENT_WORKERS,
            () -> paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT));

    assertThat(results.successCount()).as("지갑을 잠그게 됐어도 S1 의 판정은 그대로다").isEqualTo(1);
    assertThat(results.failuresOf(InvalidPaymentStatusException.class))
        .as("나머지는 CAS 에서 0 건 갱신으로 탈락한다")
        .hasSize(SAME_PAYMENT_WORKERS - 1);
    assertThat(results.failuresOtherThan(InvalidPaymentStatusException.class)).isEmpty();

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - AMOUNT);
    assertThat(payLedgerCount(Seeds.MEMBER_ID_1)).isEqualTo(1);
    assertApprovedWithTimestamp(1);
    assertOneLedgerRowPerApprovedPayment();

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
        .as("성공한 결제의 PAY 원장은 결제당 정확히 1건이다")
        .isEmpty();
  }

  private void assertRejectedLeftNothingBehind(int expected) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'READY'", Integer.class))
        .as("거절된 결제는 IN_PROGRESS 가 아니라 READY 로 롤백된다")
        .isEqualTo(expected);
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

  private long ledgerSumOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(l.amount), 0) FROM wallet_ledger l"
            + " JOIN wallet w ON w.id = l.wallet_id WHERE w.member_id = ?",
        Long.class,
        memberId);
  }

  private int payLedgerCount(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id"
            + " WHERE w.member_id = ? AND l.type = 'PAY'",
        Integer.class,
        memberId);
  }
}
