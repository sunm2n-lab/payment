package com.sunm2n.payment.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.payment.application.PaymentService;
import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.PaymentMethod;
import com.sunm2n.payment.support.AbstractIntegrationTest;
import com.sunm2n.payment.support.Seeds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 입력 길이와 경로 변수 형식 검증.
 *
 * <p>DB 컬럼 길이를 넘는 문자열이 검증을 통과하면 INSERT 시점에 터져 500 이 된다. 의도한 동시성 결함과 무관한 입력 검증 누락이므로 API 경계에서 400 으로
 * 막는다.
 */
@AutoConfigureMockMvc
class ApiInputValidationTest extends AbstractIntegrationTest {

  private static final String API_KEY_HEADER = MerchantAuthInterceptor.API_KEY_HEADER;

  @Autowired private MockMvc mockMvc;
  @Autowired private PaymentService paymentService;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("orderId 가 컬럼 길이(64)를 넘으면 500 이 아니라 400 이다")
  void orderIdTooLong() throws Exception {
    mockMvc
        .perform(
            post("/v1/payments")
                .header(API_KEY_HEADER, Seeds.MERCHANT_1_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPaymentJson("o".repeat(65))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("orderId 가 정확히 64자면 통과한다")
  void orderIdAtLimit() throws Exception {
    mockMvc
        .perform(
            post("/v1/payments")
                .header(API_KEY_HEADER, Seeds.MERCHANT_1_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPaymentJson("o".repeat(64))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("취소 reason 이 컬럼 길이(255)를 넘으면 500 이 아니라 400 이다")
  void cancelReasonTooLong() throws Exception {
    String paymentKey = confirmedCardPaymentKey("order-reason-1");

    mockMvc
        .perform(
            post("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header(API_KEY_HEADER, Seeds.MERCHANT_1_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelAmount\":1000,\"reason\":\"" + "r".repeat(256) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("취소 reason 이 정확히 255자면 통과한다")
  void cancelReasonAtLimit() throws Exception {
    String paymentKey = confirmedCardPaymentKey("order-reason-2");

    mockMvc
        .perform(
            post("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header(API_KEY_HEADER, Seeds.MERCHANT_1_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelAmount\":1000,\"reason\":\"" + "r".repeat(255) + "\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("경로 변수 형식 오류도 공통 에러 응답을 따른다")
  void pathVariableTypeMismatch() throws Exception {
    mockMvc
        .perform(
            get("/v1/wallets/{memberId}", "abc").header(API_KEY_HEADER, Seeds.MERCHANT_1_API_KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").exists());
  }

  private String createPaymentJson(String orderId) {
    return "{\"orderId\":\"" + orderId + "\",\"amount\":10000,\"method\":\"CARD\"}";
  }

  private String confirmedCardPaymentKey(String orderId) {
    Payment created = paymentService.create(merchantId, orderId, 10_000L, PaymentMethod.CARD, null);
    paymentService.confirm(merchantId, created.getPaymentKey(), orderId, 10_000L);
    return created.getPaymentKey();
  }
}
