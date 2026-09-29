package com.sunm2n.pay.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.Seeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S6 회귀 11 — 형식 밖의 주문 id 로 생성하면 400 이고 결제가 만들어지지 않는다 ({@code docs/plan/S6.md} 4.4, 6절).
 *
 * <p>한 사례가 한 규칙만 어기게 만든다. 길이 밖의 사례는 문자 규칙 안의 값으로, 문자 밖의 사례는 길이 규칙 안의 값으로 만든다. 그래야 400 이 어느 규칙 때문인지
 * 섞이지 않는다.
 */
class OrderIdFormatApiTest extends AbstractApiTest {

  private static final String KEY = Seeds.MERCHANT_1_API_KEY;

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @ValueSource(
      strings = {
        "café-order", // 악센트 (비ASCII)
        "order-1 ", // 뒤 공백
        " order-1", // 앞 공백
        "order/1", // 경로 구분자
        "order.1", // 규칙 밖 ASCII 문장부호
        "", // 빈 값
        "abcde" // 5자
      })
  @DisplayName("회귀 11 - 규칙 밖의 주문 id 는 400 INVALID_REQUEST 이고 결제는 0건이다")
  void outsideRuleIsRejected(String orderId) throws Exception {
    createPayment(KEY, createBody(orderId, 10_000L, "CARD"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(paymentCount()).isZero();
  }

  @Test
  @DisplayName("회귀 11 - 65자는 400 이다 (문자 규칙 안)")
  void sixtyFiveCharactersAreRejected() throws Exception {
    createPayment(KEY, createBody("o".repeat(65), 10_000L, "CARD"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(paymentCount()).isZero();
  }

  @Test
  @DisplayName("회귀 11 - orderId 가 없으면 400 이다")
  void missingOrderIdIsRejected() throws Exception {
    createPayment(KEY, "{\"amount\":10000,\"method\":\"CARD\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(paymentCount()).isZero();
  }

  @Test
  @DisplayName("회귀 11 - 6자와 64자는 허용한다")
  void boundaryLengthsAreAccepted() throws Exception {
    createPayment(KEY, createBody("abc-12", 10_000L, "CARD")).andExpect(status().isOk());
    createPayment(KEY, createBody("A_" + "z".repeat(62), 10_000L, "CARD"))
        .andExpect(status().isOk());

    assertThat(paymentCount()).isEqualTo(2);
  }

  private int paymentCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
  }
}
