package com.sunm2n.pay.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sunm2n.pay.api.MerchantAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP 계층 테스트의 베이스.
 *
 * <p>4xx 매핑은 인터셉터와 {@code ApiExceptionHandler} 가 결정하므로 서비스 테스트로는 검증되지 않는다. 실제 상태 코드를 확인하려면 MockMvc
 * 로 요청을 태워야 한다.
 */
@AutoConfigureMockMvc
public abstract class AbstractApiTest extends AbstractIntegrationTest {

  protected static final String API_KEY_HEADER = MerchantAuthInterceptor.API_KEY_HEADER;

  @Autowired protected MockMvc mockMvc;

  protected ResultActions createPayment(String apiKey, String body) throws Exception {
    return mockMvc.perform(json(post("/v1/payments"), apiKey, body));
  }

  protected ResultActions confirmPayment(String apiKey, String body) throws Exception {
    return mockMvc.perform(json(post("/v1/payments/confirm"), apiKey, body));
  }

  protected ResultActions cancelPayment(String apiKey, String paymentKey, String body)
      throws Exception {
    return mockMvc.perform(json(post("/v1/payments/{key}/cancel", paymentKey), apiKey, body));
  }

  protected ResultActions getPayment(String apiKey, String paymentKey) throws Exception {
    return mockMvc.perform(withKey(get("/v1/payments/{key}", paymentKey), apiKey));
  }

  /** 경로 변수를 그대로 넘긴다. 형식이 잘못된 값(예: {@code "abc"})을 보낼 때 쓴다. */
  protected ResultActions getWalletRaw(String apiKey, Object memberId) throws Exception {
    return mockMvc.perform(withKey(get("/v1/wallets/{id}", memberId), apiKey));
  }

  protected ResultActions chargeWallet(String apiKey, long memberId, String body) throws Exception {
    return mockMvc.perform(json(post("/v1/wallets/{id}/charge", memberId), apiKey, body));
  }

  protected ResultActions getWallet(String apiKey, long memberId) throws Exception {
    return mockMvc.perform(withKey(get("/v1/wallets/{id}", memberId), apiKey));
  }

  /** 결제를 만들고 발급된 paymentKey 를 돌려준다. */
  protected String createPaymentKey(String apiKey, String body) throws Exception {
    String response = createPayment(apiKey, body).andReturn().getResponse().getContentAsString();
    return JsonPath.read(response, "$.paymentKey");
  }

  protected static String createBody(String orderId, long amount, String method) {
    return "{\"orderId\":\"%s\",\"amount\":%d,\"method\":\"%s\"}"
        .formatted(orderId, amount, method);
  }

  protected static String createMoneyBody(String orderId, long amount, long memberId) {
    return "{\"orderId\":\"%s\",\"amount\":%d,\"method\":\"MONEY\",\"memberId\":%d}"
        .formatted(orderId, amount, memberId);
  }

  protected static String confirmBody(String paymentKey, String orderId, long amount) {
    return "{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d}"
        .formatted(paymentKey, orderId, amount);
  }

  protected static String cancelBody(long cancelAmount) {
    return "{\"cancelAmount\":%d}".formatted(cancelAmount);
  }

  protected static String amountBody(long amount) {
    return "{\"amount\":%d}".formatted(amount);
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder builder, String apiKey, String body) {
    return withKey(builder, apiKey).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static MockHttpServletRequestBuilder withKey(
      MockHttpServletRequestBuilder builder, String apiKey) {
    return apiKey == null ? builder : builder.header(API_KEY_HEADER, apiKey);
  }
}
