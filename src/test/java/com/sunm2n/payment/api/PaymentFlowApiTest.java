package com.sunm2n.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.payment.support.AbstractApiTest;
import com.sunm2n.payment.support.Seeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 완료 기준의 정상 흐름: 생성 → 승인 → 부분취소 → 조회를 CARD 와 MONEY 각각.
 *
 * <p>응답 코드뿐 아니라 승인 횟수, 잔액·원장 합계, 취소 합계·잔여 금액까지 확인한다 (SCENARIO 90행).
 */
class PaymentFlowApiTest extends AbstractApiTest {

  private static final String KEY = Seeds.MERCHANT_1_API_KEY;
  private static final long AMOUNT = 10_000L;

  @Test
  @DisplayName("CARD: 생성(READY) → 승인(DONE) → 부분취소(PARTIAL_CANCELED) → 조회")
  void cardHappyPath() throws Exception {
    String paymentKey = createPaymentKey(KEY, createBody("card-flow", AMOUNT, "CARD"));

    getPayment(KEY, paymentKey)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.balanceAmount").value(AMOUNT))
        .andExpect(jsonPath("$.cardApprovalNo").doesNotExist());

    confirmPayment(KEY, confirmBody(paymentKey, "card-flow", AMOUNT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.approvedAt").exists())
        .andExpect(jsonPath("$.cardApprovalNo").isNotEmpty());

    // 카드사 승인은 정확히 한 번
    assertThat(fakeCardApprovalClient.getApproveCount()).isEqualTo(1);

    cancelPayment(KEY, paymentKey, cancelBody(3_000L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PARTIAL_CANCELED"))
        .andExpect(jsonPath("$.balanceAmount").value(7_000L));

    assertThat(fakeCardApprovalClient.getCancelCount()).isEqualTo(1);

    getPayment(KEY, paymentKey)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PARTIAL_CANCELED"))
        .andExpect(jsonPath("$.balanceAmount").value(7_000L))
        .andExpect(jsonPath("$.amount").value(AMOUNT));

    assertThat(cancelSumOf(paymentKey)).isEqualTo(3_000L);
    // CARD 는 지갑을 건드리지 않는다
    assertThat(ledgerCount()).isZero();
    invariants.assertAll();
  }

  @Test
  @DisplayName("MONEY: 충전 → 생성 → 승인(차감+PAY) → 부분취소(환불+REFUND) → 조회")
  void moneyHappyPath() throws Exception {
    chargeWallet(KEY, Seeds.MEMBER_ID_1, amountBody(30_000L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(30_000L));

    String paymentKey =
        createPaymentKey(KEY, createMoneyBody("money-flow", AMOUNT, Seeds.MEMBER_ID_1));

    confirmPayment(KEY, confirmBody(paymentKey, "money-flow", AMOUNT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"));

    getWallet(KEY, Seeds.MEMBER_ID_1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(20_000L));

    cancelPayment(KEY, paymentKey, cancelBody(4_000L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PARTIAL_CANCELED"))
        .andExpect(jsonPath("$.balanceAmount").value(6_000L));

    getWallet(KEY, Seeds.MEMBER_ID_1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(24_000L));

    // 원장 3건: CHARGE +30,000 / PAY -10,000 / REFUND +4,000 = 24,000
    assertThat(ledgerCount()).isEqualTo(3);
    assertThat(ledgerSum()).isEqualTo(24_000L);
    assertThat(cancelSumOf(paymentKey)).isEqualTo(4_000L);
    // MONEY 는 카드사를 부르지 않는다
    assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
    assertThat(fakeCardApprovalClient.getCancelCount()).isZero();
    invariants.assertAll();
  }

  @Test
  @DisplayName("부분취소를 반복해 잔여 금액이 0 이 되면 CANCELED 가 되고 취소 합계는 승인 금액과 같다")
  void repeatedPartialCancelsEndAsCanceled() throws Exception {
    String paymentKey = createPaymentKey(KEY, createBody("card-full", AMOUNT, "CARD"));
    confirmPayment(KEY, confirmBody(paymentKey, "card-full", AMOUNT)).andExpect(status().isOk());

    cancelPayment(KEY, paymentKey, cancelBody(3_000L))
        .andExpect(jsonPath("$.status").value("PARTIAL_CANCELED"));
    cancelPayment(KEY, paymentKey, cancelBody(7_000L))
        .andExpect(jsonPath("$.status").value("CANCELED"))
        .andExpect(jsonPath("$.balanceAmount").value(0));

    assertThat(cancelSumOf(paymentKey)).isEqualTo(AMOUNT);
    assertThat(fakeCardApprovalClient.getCancelCount()).isEqualTo(2);
    invariants.assertAll();
  }

  private long cancelSumOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(c.cancel_amount), 0) FROM payment_cancel c"
            + " JOIN payment p ON p.id = c.payment_id WHERE p.payment_key = ?",
        Long.class,
        paymentKey);
  }

  private int ledgerCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_ledger", Integer.class);
  }

  private long ledgerSum() {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(amount), 0) FROM wallet_ledger", Long.class);
  }
}
