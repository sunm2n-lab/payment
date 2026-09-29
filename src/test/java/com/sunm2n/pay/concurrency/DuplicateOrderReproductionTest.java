package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.pay.merchant.api.auth.MerchantAuthInterceptor;
import com.sunm2n.pay.support.AbstractV5SchemaTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.support.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S6 0·0' 단계 재현 — 주문 유일성과 비교 규칙이 없다 ({@code docs/plan/S6.md} 3절).
 *
 * <ul>
 *   <li>0 — 같은 가맹점이 같은 {@code orderId} 로 결제를 두 번 만들 수 있다. 주문 기반 조회·취소는 결과가 2행이라 500 이다. 하나를 골라 취소하지
 *       않는다
 *   <li>0' — {@code order_id} 의 collation({@code utf8mb4_0900_ai_ci})은 대소문자를 무시한다. {@code ORDER-1}
 *       조회가 {@code order-1} 의 결제를 고르고, 둘 다 있으면 2행이라 500 이다
 * </ul>
 *
 * <p>처음부터 V5 DB 에서 돈다. V7 의 unique·{@code ascii_bin} 이 들어와도 이 결과는 바뀌지 않는다.
 */
class DuplicateOrderReproductionTest extends AbstractV5SchemaTest {

  private static final String KEY = Seeds.MERCHANT_1_API_KEY;

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("0 단계 - 같은 가맹점·같은 orderId 순차 생성이 둘 다 성공하고, 주문 기반 조회·취소는 500 이다")
  void duplicateOrderIsAcceptedAndBreaksOrderLookup() throws Exception {
    confirmedCard("order-s6-0");
    confirmedCard("order-s6-0");
    assertThat(paymentsOf("order-s6-0")).isEqualTo(2);
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    expectInternalError(
        mockMvc.perform(get("/v1/payments/orders/{id}", "order-s6-0").header(header(), KEY)));
    expectInternalError(cancelByOrder("order-s6-0"));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient))
        .as("어느 결제도 취소되지 않았다")
        .isEqualTo(before);
  }

  @Test
  @DisplayName("0' 단계 - order-1 로 만든 결제가 ORDER-1 조회에 잡히고, ORDER-1 도 만들면 2행이라 500 이다")
  void orderIdComparisonIgnoresCase() throws Exception {
    confirmedCard("order-s6-1");

    mockMvc
        .perform(get("/v1/payments/orders/{id}", "ORDER-S6-1").header(header(), KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value("order-s6-1"));

    confirmedCard("ORDER-S6-1");
    assertThat(jdbcTemplate.queryForList("SELECT order_id FROM payment ORDER BY id", String.class))
        .as("대소문자만 다른 두 주문이 각자 생성된다")
        .containsExactly("order-s6-1", "ORDER-S6-1");
    expectInternalError(
        mockMvc.perform(get("/v1/payments/orders/{id}", "ORDER-S6-1").header(header(), KEY)));
    expectInternalError(cancelByOrder("order-s6-1"));
  }

  private void confirmedCard(String orderId) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/v1/payments")
                    .header(header(), KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"orderId\":\"%s\",\"amount\":10000,\"method\":\"CARD\"}"
                            .formatted(orderId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String paymentKey = com.jayway.jsonpath.JsonPath.read(response, "$.paymentKey");
    mockMvc
        .perform(
            post("/v1/payments/confirm")
                .header(header(), KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":10000}"
                        .formatted(paymentKey, orderId)))
        .andExpect(status().isOk());
  }

  private ResultActions cancelByOrder(String orderId) throws Exception {
    return mockMvc.perform(
        post("/v1/payments/orders/{id}/cancel", orderId)
            .header(header(), KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cancelAmount\":1000}"));
  }

  private static void expectInternalError(ResultActions result) throws Exception {
    result
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
  }

  private int paymentsOf(String orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
  }

  private static String header() {
    return MerchantAuthInterceptor.API_KEY_HEADER;
  }
}
