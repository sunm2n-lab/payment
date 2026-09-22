package com.sunm2n.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.PaymentMethod;
import com.sunm2n.payment.domain.exception.InsufficientBalanceException;
import com.sunm2n.payment.support.AbstractIntegrationTest;
import com.sunm2n.payment.support.ConcurrencyGate;
import com.sunm2n.payment.support.ConcurrentRunner;
import com.sunm2n.payment.support.Seeds;
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
 * S2 원자적 감산 비교 실험 (SCENARIO 139~140행).
 *
 * <p>재현과 <b>같은 시작 데이터·같은 동기화 지점</b>에서 잔액 변경 전략만 바꿔 독립 실행한다. 달라지는 것이 전략 하나뿐이어야 결과의 차이를 그 하나로 설명할 수
 * 있다.
 *
 * <p>비교의 값은 "고쳐졌다"가 아니라 <b>무엇이 고쳐지고 무엇이 안 고쳐졌는가</b>에 있다. 원자적 감산은 차감 유실을 없애서 잔액과 원장 합계를 일치시키지만, 검사와
 * 감산이 따로라 잔액이 음수가 된다.
 */
class WalletDecrementComparisonTest extends AbstractIntegrationTest {

  private static final int WORKERS = 4;
  private static final String ORDER_ID = "order-s2";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 10_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("atomicDebitConfirmer")
  private PaymentConfirmer atomicDebitConfirmer;

  @Autowired
  @Qualifier("guardedDebitConfirmer")
  private PaymentConfirmer guardedDebitConfirmer;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("실험 1 - 원자적 감산은 차감 유실을 없애지만 검사를 다 통과한 4건이 잔액을 -2,000 으로 만든다")
  void atomicDecrementKeepsTheSumButGoesNegative() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> paymentKeys = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    // 재현과 같은 지점이다. 검사용 스칼라 조회(findBalanceById)가 WALLET_READ 다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> atomicDebitConfirmer.confirm(merchantId, paymentKeys.poll(), ORDER_ID, AMOUNT));

    assertThat(results.failures()).as("검사 시점에는 모두 10,000 을 보므로 아무도 거절되지 않는다").isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);

    assertThat(balanceOf(Seeds.MEMBER_ID_1))
        .as("네 번의 감산이 모두 반영된다 - 차감 유실은 사라졌다")
        .isEqualTo(INITIAL_BALANCE - WORKERS * AMOUNT);
    assertThat(payLedgerCount(Seeds.MEMBER_ID_1)).isEqualTo(WORKERS);
    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1))
        .as("잔액과 원장 합계는 일치한다. 깨지는 것은 '음수가 아니다' 쪽 하나다")
        .isEqualTo(balanceOf(Seeds.MEMBER_ID_1));

    assertApprovedWithTimestamp(WORKERS);

    assertThatThrownBy(() -> invariants.assertWalletBalanceMatchesLedger())
        .as("잔액 -2,000 - DB 가 막지 않으므로 Invariants 가 검출한다")
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("실험 2 - 검사까지 UPDATE 안으로 넣으면 성공 3 / 부족 1 로 갈리고 불변식이 모두 유지된다")
  void guardedDecrementRejectsTheFourthRequest() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> paymentKeys = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    // 이 전략은 지갑을 읽지 않으므로 WALLET_READ 에는 아무도 오지 않는다. 결제 조회 시점으로 출발을 맞춘다.
    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> guardedDebitConfirmer.confirm(merchantId, paymentKeys.poll(), ORDER_ID, AMOUNT));

    assertThat(results.successCount()).as("10,000 으로 3,000 짜리는 세 건까지다").isEqualTo(3);
    assertThat(results.failuresOf(InsufficientBalanceException.class))
        .as("네 번째는 최신 잔액으로 조건이 재평가되어 0 건으로 탈락한다")
        .hasSize(1);
    assertThat(results.failuresOtherThan(InsufficientBalanceException.class))
        .as("예상 밖 오류는 없다")
        .isEmpty();
    assertThat(results.failures().get(0))
        .as("탈락자는 아무 행도 바꾸지 않았고 재조회는 옛 스냅샷을 준다 - 잔액을 주장하지 않는다")
        .hasMessageNotContaining("balance=");

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - 3 * AMOUNT);
    assertThat(payLedgerCount(Seeds.MEMBER_ID_1)).isEqualTo(3);
    assertApprovedWithTimestamp(3);
    assertRejectedLeftNothingBehind();

    invariants.assertAll();
  }

  /** 실패 경로 공통 단언 ({@code docs/plan/S2.md} 5.1) — 거절된 결제는 READY 로 남고 원장도 남기지 않는다. */
  private void assertRejectedLeftNothingBehind() {
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT p.payment_key FROM payment p WHERE p.status <> 'DONE'", String.class))
        .as("거절된 결제는 IN_PROGRESS 가 아니라 READY 로 롤백된다")
        .hasSize(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger l JOIN payment p ON p.id = l.payment_id"
                    + " WHERE p.status <> 'DONE'",
                Integer.class))
        .as("거절된 결제의 원장은 0건이다")
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'READY'", Integer.class))
        .isEqualTo(1);
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

  /**
   * 성공한 결제가 DONE 이고 {@code approved_at} 이 남았는지 본다.
   *
   * <p>이 단언이 이 실험의 안전장치다. 원자적 UPDATE 가 영속성 컨텍스트를 비우면 관리 중이던 결제가 detached 되어 결제가 {@code IN_PROGRESS}
   * 로 커밋되는데, <b>잔액과 원장만 보면 기대값이 그대로 통과한다</b> ({@code docs/plan/S2.md} 2.3).
   */
  private void assertApprovedWithTimestamp(int expected) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'DONE' AND approved_at IS NOT NULL",
                Integer.class))
        .as("승인에 성공한 결제는 DONE + approved_at 이다")
        .isEqualTo(expected);
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
