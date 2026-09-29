package com.sunm2n.pay.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.Seeds;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 오류 판정 순서의 기준선.
 *
 * <p>인증 인터셉터, 핸들러 매핑, 메시지 변환 중 무엇이 먼저 요청을 거절하는지를 현재 MVC 설정과 엔드포인트 매핑에서 실측한 그대로 고정한다. 애플리케이션 전체 규칙이
 * 아니라 구조 이동과 오류 응답 변경(#21)의 회귀 기준이다. 예를 들어 매핑에 {@code consumes} 를 붙이면 415 는 핸들러 매핑 단계로 올라가 인증보다 먼저
 * 판정된다.
 *
 * <ul>
 *   <li>405 는 인증보다 먼저다. 핸들러 매핑 단계에서 나므로 인터셉터에 닿지 않는다
 *   <li>{@code /v1/**} 아래의 404·415 는 인증 뒤다. 정적 리소스 핸들러 매핑에도 인터셉터가 적용되므로 없는 경로도 키가 없으면 401 이다
 * </ul>
 *
 * <p>404·405·415 의 본문은 지금 비어 있지만 단언하지 않는다. 오류 응답 규약 변경이 채울 부분이다.
 */
class RoutingContractApiTest extends AbstractApiTest {

  private static final String KEY = Seeds.MERCHANT_1_API_KEY;

  @Nested
  @DisplayName("GET /v1/payments/confirm - GET 패턴 {paymentKey} 에 매칭된다")
  class GetOnPostOnlyPath {

    @Test
    @DisplayName("키가 없으면 401")
    void withoutKey() throws Exception {
      perform(get("/v1/payments/confirm"), null)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("유효한 키면 paymentKey=confirm 인 결제를 찾다가 404")
    void withKey() throws Exception {
      perform(get("/v1/payments/confirm"), KEY)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.message").value(Matchers.containsString("paymentKey=confirm")));
    }
  }

  @Nested
  @DisplayName("405 - 인증보다 먼저 판정된다")
  class MethodNotAllowed {

    @Test
    @DisplayName("PUT /v1/payments/confirm, 키 없음 → 405, Allow = {POST, GET}")
    void putWithoutKey() throws Exception {
      MvcResult result =
          perform(put("/v1/payments/confirm"), null)
              .andExpect(status().isMethodNotAllowed())
              .andReturn();

      // 서로 다른 두 패턴(POST /confirm, GET /{paymentKey})의 합이다. 순서는 보존 대상이 아니다.
      assertThat(allow(result)).containsExactlyInAnyOrder("POST", "GET");
    }

    @Test
    @DisplayName("PUT /v1/payments/confirm, 유효한 키 → 405, Allow = {POST, GET}")
    void putWithKey() throws Exception {
      MvcResult result =
          perform(put("/v1/payments/confirm"), KEY)
              .andExpect(status().isMethodNotAllowed())
              .andReturn();

      assertThat(allow(result)).containsExactlyInAnyOrder("POST", "GET");
    }

    @Test
    @DisplayName("POST /v1/wallets/1, 유효한 키 → 405, Allow = {GET}")
    void postOnWallet() throws Exception {
      MvcResult result =
          perform(post("/v1/wallets/{id}", Seeds.MEMBER_ID_1), KEY)
              .andExpect(status().isMethodNotAllowed())
              .andReturn();

      assertThat(allow(result)).containsExactly("GET");
    }
  }

  @Nested
  @DisplayName("404 - 없는 경로")
  class NoRoute {

    @Test
    @DisplayName("GET /v1/nope, 키 없음 → 인터셉터가 먼저 401")
    void underV1WithoutKey() throws Exception {
      perform(get("/v1/nope"), null)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /v1/nope, 유효한 키 → 404")
    void underV1WithKey() throws Exception {
      perform(get("/v1/nope"), KEY).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /nope, 키 없음 → 인터셉터 범위 밖이라 404")
    void outsideV1() throws Exception {
      perform(get("/nope"), null).andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("415 - 인증 뒤에 판정된다")
  class UnsupportedMediaType {

    @Test
    @DisplayName("POST /v1/payments text/plain, 키 없음 → 401")
    void withoutKey() throws Exception {
      perform(textBody(post("/v1/payments")), null)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("POST /v1/payments text/plain, 유효한 키 → 415")
    void withKey() throws Exception {
      perform(textBody(post("/v1/payments")), KEY).andExpect(status().isUnsupportedMediaType());
    }
  }

  /**
   * S6 의 주문 기반 경로 ({@code docs/plan/S6.md} 4.1). {@code GET /{paymentKey}} 는 세그먼트가 하나, {@code POST
   * /{paymentKey}/cancel} 은 둘이라 {@code /orders/{orderId}}, {@code /orders/{orderId}/cancel} 과 겹치지
   * 않는다. 세그먼트 수가 같은 {@code /orders}, {@code /orders/cancel} 은 기존 매핑이 받는다.
   */
  @Nested
  @DisplayName("주문 기반 경로 - 기존 매핑과 겹치지 않는다")
  class OrderRoutes {

    @Test
    @DisplayName("GET /v1/payments/orders 는 GET /{paymentKey} 가 받는다 - paymentKey=orders 로 404")
    void ordersWithoutIdIsPaymentKey() throws Exception {
      perform(get("/v1/payments/orders"), KEY)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value(Matchers.containsString("paymentKey=orders")));
    }

    @Test
    @DisplayName(
        "POST /v1/payments/orders/cancel 은 POST /{paymentKey}/cancel 이 받는다 - paymentKey=orders 로 404")
    void ordersCancelWithoutIdIsPaymentKeyCancel() throws Exception {
      perform(jsonBody(post("/v1/payments/orders/cancel"), "{\"cancelAmount\":1000}"), KEY)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value(Matchers.containsString("paymentKey=orders")));
    }

    @Test
    @DisplayName("GET /v1/payments/orders/cancel 은 주문 조회다 - orderId=cancel(6자) 로 404")
    void getOrdersCancelIsOrderLookup() throws Exception {
      perform(get("/v1/payments/orders/cancel"), KEY)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value(Matchers.containsString("orderId=cancel")));
    }

    @Test
    @DisplayName("형식 밖의 주문 id, 키 없음 → 인증이 먼저 401")
    void authBeforeOrderIdRule() throws Exception {
      perform(get("/v1/payments/orders/abcde"), null)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("본문 검증이 주문 id 형식보다 먼저다 - 인자 해석 단계에서 판정된다")
    void bodyBeforeOrderIdRule() throws Exception {
      perform(jsonBody(post("/v1/payments/orders/abcde/cancel"), "{\"cancelAmount\":0}"), KEY)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value(Matchers.startsWith("cancelAmount")));
    }

    @Test
    @DisplayName("주문 id 형식이 멱등키 형식보다 먼저다")
    void orderIdRuleBeforeIdempotencyKey() throws Exception {
      perform(
              jsonBody(post("/v1/payments/orders/abcde/cancel"), "{\"cancelAmount\":1000}")
                  .header("Idempotency-Key", "a,b"),
              KEY)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value(Matchers.startsWith("orderId")));
    }

    @Test
    @DisplayName("PUT /v1/payments/orders/order-1 → 405, Allow = {GET}")
    void putOnOrder() throws Exception {
      MvcResult result =
          perform(put("/v1/payments/orders/order-1"), KEY)
              .andExpect(status().isMethodNotAllowed())
              .andReturn();

      assertThat(allow(result)).containsExactly("GET");
    }
  }

  private ResultActions perform(MockHttpServletRequestBuilder builder, String apiKey)
      throws Exception {
    return mockMvc.perform(apiKey == null ? builder : builder.header(API_KEY_HEADER, apiKey));
  }

  private static MockHttpServletRequestBuilder jsonBody(
      MockHttpServletRequestBuilder builder, String body) {
    return builder.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static MockHttpServletRequestBuilder textBody(MockHttpServletRequestBuilder builder) {
    return builder.contentType(MediaType.TEXT_PLAIN).content("plain");
  }

  private static Set<String> allow(MvcResult result) {
    String header = result.getResponse().getHeader(HttpHeaders.ALLOW);
    assertThat(header).as("Allow 헤더").isNotNull();
    return Arrays.stream(header.split(","))
        .map(String::trim)
        .filter(method -> !method.isEmpty())
        .collect(Collectors.toSet());
  }
}
