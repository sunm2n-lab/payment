package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.Seeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S5 0단계 — 커밋된 취소를 가맹점이 다시 보내면 두 번 처리된다.
 *
 * <p>응답 유실은 흉내 내지 않는다. 서버 입장에서 "응답을 못 받아 다시 보낸 요청" 은 "같은 요청을 한 번 더 보낸 것" 과 구분되지 않는다. 그래서 3,000 취소를
 * <b>순차로</b> 두 번 보낸다.
 *
 * <p>S3 의 잠금은 금액 상한을 지킨다. 두 번째 요청도 상한 안이라 정당한 요청처럼 통과한다. 불변식은 깨지지 않는다 — 깨진 것은 "같은 요청은 한 번만 실행된다" 는
 * 기대다.
 *
 * <p><b>최신 컨텍스트에 둔다.</b> {@code Idempotency-Key} 는 선택 헤더라서 헤더 없이 재전송하면 두 번 처리된다는 것은 본선에서도 계속 성립하는
 * 동작이다 ({@code docs/plan/S5.md} 3절). V5 이후에도 green 이어야 한다.
 *
 * <p><b>이 테스트는 결함이 재현될 때 green 이다.</b>
 */
class CancelResendReproductionTest extends AbstractApiTest {

  private static final long CHARGE = 100_000L;
  private static final long AMOUNT = 10_000L;
  private static final long CANCEL = 3_000L;

  @Test
  @DisplayName("0단계 - 키 없이 3,000 취소를 두 번 보내면 취소 이력 2건, 잔여 4,000, REFUND 원장 2건이 된다")
  void resentCancelWithoutKeyRunsTwice() throws Exception {
    String paymentKey = confirmedMoneyPayment("order-s5-0");

    cancelPayment(Seeds.MERCHANT_1_API_KEY, paymentKey, cancelBody(CANCEL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanceAmount").value(AMOUNT - CANCEL));
    cancelPayment(Seeds.MERCHANT_1_API_KEY, paymentKey, cancelBody(CANCEL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanceAmount").value(AMOUNT - 2 * CANCEL));

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(2);
    assertThat(refundLedgerCount()).isEqualTo(2);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(CHARGE - AMOUNT + 2 * CANCEL);
    invariants.assertAll();
  }

  private String confirmedMoneyPayment(String orderId) throws Exception {
    chargeWallet(Seeds.MERCHANT_1_API_KEY, Seeds.MEMBER_ID_1, amountBody(CHARGE))
        .andExpect(status().isOk());
    String paymentKey =
        createPaymentKey(
            Seeds.MERCHANT_1_API_KEY, createMoneyBody(orderId, AMOUNT, Seeds.MEMBER_ID_1));
    confirmPayment(Seeds.MERCHANT_1_API_KEY, confirmBody(paymentKey, orderId, AMOUNT))
        .andExpect(status().isOk());
    return paymentKey;
  }

  private int cancelRowsOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }

  private int refundLedgerCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'REFUND'", Integer.class);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }
}
