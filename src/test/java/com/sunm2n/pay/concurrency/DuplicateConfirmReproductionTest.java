package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.confirmation.NaivePaymentConfirmer;
import com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * S1 재현 — 같은 결제가 두 번 승인된다.
 *
 * <p>naive 구현의 READY 검사는 잠금 없는 consistent read 이고 DONE 갱신은 flush 시점의 UPDATE 다. 둘 사이에 원자성이 없으므로 같은
 * {@code paymentKey} 로 동시에 들어온 confirm 이 모두 READY 를 읽고 모두 승인에 성공한다.
 *
 * <p><b>이 테스트는 결함이 재현될 때 green 이다.</b> 깨진 결과를 그대로 assert 해서 고정한다. FK 0건이나 인덱스 부재를 테스트로 고정하는 것과 같은
 * 성격이며, 개선 후에도 naive 구현이 계속 이 결과를 내는지 확인하는 기록이 된다.
 *
 * <p>워커는 5개다. 게이트에서 대기하는 워커가 각자 커넥션을 점유하므로 {@code maximum-pool-size} 10 보다 작아야 한다.
 *
 * <p>승인은 {@link NaivePaymentConfirmer} 를 직접 호출한다. 기본 구현은 S1 에서 조건부 UPDATE 로 바뀌었으므로 {@link
 * PaymentService} 를 거치면 개선된 경로를 타게 된다. 실패 버전을 코드에 남겨 둔 이유가 바로 이 재현을 계속 실행하기 위해서다.
 */
class DuplicateConfirmReproductionTest extends AbstractIntegrationTest {

  private static final int WORKERS = 5;
  private static final String ORDER_ID = "order-s1";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 10_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("naivePaymentConfirmer")
  private PaymentConfirmer naiveConfirmer;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("CARD - 동시 승인이 모두 성공해 카드사를 N 번 호출하고 승인번호는 마지막 것만 남는다")
  void cardIsApprovedOncePerRequest() {
    String paymentKey = createPayment(PaymentMethod.CARD, null);

    // 모두 READY 를 읽은 시점을 맞춘다.
    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results = confirmConcurrently(paymentKey);

    assertThat(results.failures()).as("naive 구현에서는 아무도 거절되지 않는다").isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);

    assertThat(fakeCardApprovalClient.getApproveCount())
        .as("카드사 승인이 결제 1건에 %d 번 일어난다", WORKERS)
        .isEqualTo(WORKERS);

    List<String> issued =
        results.successes().stream().map(Payment::getCardApprovalNo).distinct().toList();
    assertThat(issued).as("승인번호는 %d 개가 발급된다", WORKERS).hasSize(WORKERS);

    String stored =
        jdbcTemplate.queryForObject(
            "SELECT card_approval_no FROM payment WHERE payment_key = ?", String.class, paymentKey);
    assertThat(issued).as("그 중 DB 에 남는 것은 하나뿐이다 - 나머지 승인은 추적할 수 없다").contains(stored);
    assertThat(currentStatus(paymentKey)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("MONEY - 모두 같은 잔액을 읽어 차감은 1 회지만 PAY 원장은 N 건이라 불변식 1 이 깨진다")
  void moneyLosesDeductionsButKeepsLedgerRows() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String paymentKey = createPayment(PaymentMethod.MONEY, Seeds.MEMBER_ID_1);

    // 모두 READY 를 읽고, 이어서 모두 같은 잔액을 읽은 시점까지 맞춘다.
    concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    concurrencyGate.arm(ConcurrencyGate.WALLET_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results = confirmConcurrently(paymentKey);

    assertThat(results.failures()).as("naive 구현에서는 아무도 거절되지 않는다").isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);

    assertThat(balanceOf(Seeds.MEMBER_ID_1))
        .as("모두 같은 잔액을 읽고 계산값을 저장했으므로 차감은 한 번만 반영된다")
        .isEqualTo(INITIAL_BALANCE - AMOUNT);
    assertThat(payLedgerCount(paymentKey)).as("반면 PAY 원장은 %d 건 쌓인다", WORKERS).isEqualTo(WORKERS);

    assertThatThrownBy(() -> invariants.assertWalletBalanceMatchesLedger())
        .as("잔액과 원장 합계가 어긋난다 - 이것이 S1 이 깨뜨리는 불변식이다")
        .isInstanceOf(AssertionError.class);
  }

  private String createPayment(PaymentMethod method, Long memberId) {
    return paymentService.create(merchantId, ORDER_ID, AMOUNT, method, memberId).getPaymentKey();
  }

  private ConcurrentRunner.Results<Payment> confirmConcurrently(String paymentKey) {
    return ConcurrentRunner.run(
        WORKERS, () -> naiveConfirmer.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT));
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
