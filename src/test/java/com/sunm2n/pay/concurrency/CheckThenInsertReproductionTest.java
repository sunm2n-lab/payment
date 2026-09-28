package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.cancellation.CheckThenInsertIdempotentCanceller;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractV4SchemaTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 1차 재현 — check-then-insert 는 동시 요청에서 뚫린다 (SCENARIO S5 "관찰 2", 179행).
 *
 * <pre>
 * T1  SELECT key            없음
 * T2  SELECT key            없음          ── KEY_LOOKED_UP barrier
 * T1  payment FOR UPDATE → 취소 → INSERT key → 커밋
 * T2  payment FOR UPDATE (T1 커밋까지 대기) → 잔여 7,000 을 보고 취소 → INSERT key → 커밋
 * </pre>
 *
 * <p>T2 는 T1 의 키가 커밋된 뒤에도 다시 보지 않는다. 조회는 이미 끝났다. 잠금 읽기가 최신 값을 읽으므로 금액 상한은 지켜지지만, 같은 요청이 두 번 실행된다.
 * RR 이든 RC 든 같다 — 조회와 저장 사이의 틈이 원인이다.
 *
 * <p><b>V4 컨텍스트</b>에서 돈다. 검색 인덱스가 비유일이라 키 행도 두 건 남는다.
 *
 * <p><b>이 테스트는 결함이 재현될 때 green 이다.</b>
 */
class CheckThenInsertReproductionTest extends AbstractV4SchemaTest {

  private static final long CHARGE = 100_000L;
  private static final long AMOUNT = 10_000L;
  private static final long CANCEL = 3_000L;
  private static final String KEY = "cancel-s5-1";

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;
  @Autowired private CheckThenInsertIdempotentCanceller checkThenInsertCanceller;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("1차 - 같은 키 두 요청이 모두 '없음' 을 본 뒤 진행하면 취소 2건, 키 행 2건이 된다")
  void bothRequestsSeeNoKeyAndCancelTwice() {
    String paymentKey = confirmedMoneyPayment("order-s5-1");

    concurrencyGate.arm(ConcurrencyGate.KEY_LOOKED_UP, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2, () -> checkThenInsertCanceller.cancel(merchantId, KEY, paymentKey, CANCEL, "재전송"));

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(2);

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(2);
    assertThat(balanceAmountOf(paymentKey)).isEqualTo(AMOUNT - 2 * CANCEL);
    assertThat(refundLedgerCount()).isEqualTo(2);
    assertThat(keyRowsOf(KEY)).as("비유일 인덱스라 막히지 않는다").isEqualTo(2);
    invariants.assertAll();
  }

  @Test
  @DisplayName("대조 - 순차 재전송은 키를 보고 다시 처리하지 않는다")
  void sequentialResendIsDetected() {
    String paymentKey = confirmedMoneyPayment("order-s5-1s");

    checkThenInsertCanceller.cancel(merchantId, KEY, paymentKey, CANCEL, "재전송");
    Payment resent = checkThenInsertCanceller.cancel(merchantId, KEY, paymentKey, CANCEL, "재전송");

    assertThat(resent.getBalanceAmount()).isEqualTo(AMOUNT - CANCEL);
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(keyRowsOf(KEY)).isEqualTo(1);
    invariants.assertAll();
  }

  private String confirmedMoneyPayment(String orderId) {
    walletService.charge(Seeds.MEMBER_ID_1, CHARGE);
    String paymentKey =
        paymentService
            .create(merchantId, orderId, AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
            .getPaymentKey();
    paymentService.confirm(merchantId, paymentKey, orderId, AMOUNT);
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

  private int refundLedgerCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'REFUND'", Integer.class);
  }

  private int keyRowsOf(String key) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM idempotency_key WHERE idempotency_key = ?", Integer.class, key);
  }
}
