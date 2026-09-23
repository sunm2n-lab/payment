package com.sunm2n.pay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.domain.Payment;
import com.sunm2n.pay.domain.PaymentMethod;
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
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * S2 재현 — 한 지갑의 잔액 변경이 서로를 덮어쓴다.
 *
 * <p><b>전제는 "S1 적용"</b>이다 (SCENARIO 134행). 승인은 조건부 UPDATE 를 쓰는 {@code naiveDebitConfirmer} 로 하고,
 * 결제도 워커마다 다른 것을 쓴다. 그래서 여기서 보이는 것은 S1 의 잔재가 아니라 <b>서로 다른 결제가 같은 지갑을 건드려서</b> 생기는 결함이다. S1 의 조건부
 * UPDATE 가 잠그는 것은 {@code payment} 행 하나뿐이라 지갑에는 아무 보호도 주지 않는다.
 *
 * <p>차감은 여전히 "읽고 -> 검사하고 -> 계산한 값을 저장한다" 다. 읽기는 잠금 없는 consistent read 라 모두 같은 잔액을 읽고, flush 시점의
 * {@code UPDATE wallet SET balance = <절대값>} 이 서로를 덮어쓴다.
 *
 * <p><b>이 테스트는 결함이 재현될 때 green 이다.</b> 본선이 비관적 락으로 바뀐 뒤에도 naive 전략을 고른 이 빈들은 계속 이 결과를 낸다.
 */
class WalletLostUpdateReproductionTest extends AbstractIntegrationTest {

  private static final int WORKERS = 4;
  private static final String ORDER_ID = "order-s2";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 10_000L;
  private static final long CHARGE_AMOUNT = 5_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("naiveWalletService")
  private WalletService naiveWalletService;

  @Autowired
  @Qualifier("naiveDebitConfirmer")
  private PaymentConfirmer naiveDebitConfirmer;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("서로 다른 결제 4건이 같은 잔액을 읽어 차감 한 번만 반영되고 PAY 원장은 4건 쌓인다")
  void concurrentDebitsOnTheSameWalletOverwriteEachOther() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> paymentKeys = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    // 모두 같은 잔액을 읽은 시점을 맞춘다. 결제가 서로 다르므로 CAS 는 네 워커 모두 통과시킨다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> naiveDebitConfirmer.confirm(merchantId, paymentKeys.poll(), ORDER_ID, AMOUNT));

    assertThat(results.failures()).as("잔액 10,000 에 3,000 씩이라 아무도 거절되지 않는다").isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);
    assertApproved(WORKERS);

    assertThat(balanceOf(Seeds.MEMBER_ID_1))
        .as("네 트랜잭션이 모두 10,000 을 읽고 7,000 이라는 같은 절대값을 저장했다")
        .isEqualTo(INITIAL_BALANCE - AMOUNT);
    assertThat(payLedgerCount(Seeds.MEMBER_ID_1)).as("반면 PAY 원장은 4건이다").isEqualTo(WORKERS);
    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1))
        .as("원장이 말하는 잔액은 -2,000 이다")
        .isEqualTo(INITIAL_BALANCE - WORKERS * AMOUNT);

    assertThatThrownBy(() -> invariants.assertWalletBalanceMatchesLedger())
        .as("차감 3건이 유실되어 잔액과 원장 합계가 어긋난다")
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("충전과 승인이 같은 잔액을 읽으면 한쪽 갱신이 통째로 사라진다")
  void concurrentChargeAndDebitLoseOneOfTheTwoUpdates() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String paymentKey = createPayments(1).get(0);
    AtomicInteger turn = new AtomicInteger();

    // 충전은 findByMemberId, 승인은 findById 로 잔액을 읽는다. 두 경로 모두 WALLET_READ 지점이다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_READ, 2);
    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            2,
            () ->
                turn.getAndIncrement() == 0
                    ? naiveWalletService.charge(Seeds.MEMBER_ID_1, CHARGE_AMOUNT)
                    : naiveDebitConfirmer.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT));

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(2);
    assertApproved(1);

    assertThat(ledgerSumOf(Seeds.MEMBER_ID_1))
        .as("원장은 둘 다 남는다 - 10,000 + 5,000 - 3,000")
        .isEqualTo(INITIAL_BALANCE + CHARGE_AMOUNT - AMOUNT);
    assertThat(balanceOf(Seeds.MEMBER_ID_1))
        .as("나중에 커밋한 쪽의 절대값만 남는다. 어느 쪽이 이기는지는 정해져 있지 않다")
        .isIn(INITIAL_BALANCE - AMOUNT, INITIAL_BALANCE + CHARGE_AMOUNT)
        .isNotEqualTo(INITIAL_BALANCE + CHARGE_AMOUNT - AMOUNT);

    assertThatThrownBy(() -> invariants.assertWalletBalanceMatchesLedger())
        .as("충전이 유실되든 차감이 유실되든 잔액은 원장 합계와 어긋난다")
        .isInstanceOf(AssertionError.class);
  }

  /** 워커마다 다른 결제를 쓴다. S1 의 같은 결제 경쟁과 분리해야 지갑 경쟁만 남는다 (SCENARIO 134행). */
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

  /** 성공 경로는 상태 전이까지 확인한다. 잔액과 원장만 보면 DONE·approved_at 유실을 놓친다 ({@code docs/plan/S2.md} 2.3). */
  private void assertApproved(int expected) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE status = 'DONE' AND approved_at IS NOT NULL",
                Integer.class))
        .as("성공한 결제는 DONE 이고 approved_at 이 남는다")
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
