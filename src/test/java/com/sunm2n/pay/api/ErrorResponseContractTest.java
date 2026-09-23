package com.sunm2n.pay.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.pay.bootstrap.web.ApiExceptionHandler;
import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.Seeds;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 오류 응답 규약.
 *
 * <p>모든 실패는 {@code {"code","message"}} 본문과 {@code Content-Type: application/json} 으로 나간다. 부류마다 기본
 * {@code Accept} 와 {@code Accept: text/plain} 두 가지로 보낸다 — 출구가 하나로 묶였다면 {@code Accept} 가 상태도 본문도 바꾸지
 * 못한다. 규약 이전에는 JSON 을 수용하지 않는 {@code Accept} 에서 업무 예외의 404·401 이 500 으로 바뀌었다.
 *
 * <p>{@code /v1/**} 의 404·415 는 인증 뒤에 판정되므로 유효한 키를 보낸다 ({@link RoutingContractApiTest}).
 */
class ErrorResponseContractTest extends AbstractApiTest {

  private static final String KEY = Seeds.MERCHANT_1_API_KEY;

  @Nested
  @DisplayName("업무·인증·입력 오류")
  class ApplicationErrors {

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("업무 예외 - 없는 결제 조회는 404 NOT_FOUND")
    void domainException(String accept) throws Exception {
      expectError(perform(get("/v1/payments/{key}", "no-such-key"), KEY, accept), 404, "NOT_FOUND")
          .andExpect(
              jsonPath("$.message").value(Matchers.containsString("paymentKey=no-such-key")));
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("인증 - 키가 없으면 401 UNAUTHORIZED")
    void unauthorized(String accept) throws Exception {
      expectError(
              perform(get("/v1/wallets/{id}", Seeds.MEMBER_ID_1), null, accept),
              401,
              "UNAUTHORIZED")
          .andExpect(jsonPath("$.message").value("X-API-Key 헤더가 필요합니다."));
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("경로 변수 타입 불일치 - 400 INVALID_REQUEST")
    void typeMismatch(String accept) throws Exception {
      expectError(perform(get("/v1/wallets/abc"), KEY, accept), 400, "INVALID_REQUEST")
          .andExpect(jsonPath("$.message").value("memberId: 값의 형식이 올바르지 않습니다."));
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("입력 검증 - amount 0 은 400 INVALID_REQUEST, 첫 필드 오류가 메시지다")
    void beanValidation(String accept) throws Exception {
      expectError(
              perform(jsonBody(createBody("zero", 0, "CARD")), KEY, accept), 400, "INVALID_REQUEST")
          .andExpect(jsonPath("$.message").value(Matchers.startsWith("amount: ")));
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("본문 파싱 - 깨진 JSON 은 400 INVALID_REQUEST")
    void unreadableBody(String accept) throws Exception {
      expectError(perform(jsonBody("{\"orderId\":"), KEY, accept), 400, "INVALID_REQUEST")
          .andExpect(jsonPath("$.message").value("요청 본문을 해석할 수 없습니다."));
    }
  }

  @Nested
  @DisplayName("Spring MVC 오류 - 상태·헤더는 그대로, 본문만 규약을 따른다")
  class MvcErrors {

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("GET /v1/nope, 유효한 키 → 404 NOT_FOUND")
    void noRouteUnderV1(String accept) throws Exception {
      expectError(perform(get("/v1/nope"), KEY, accept), 404, "NOT_FOUND")
          .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."));
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("GET /nope, 키 없음 → 404 NOT_FOUND")
    void noRouteOutsideV1(String accept) throws Exception {
      expectError(perform(get("/nope"), null, accept), 404, "NOT_FOUND");
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("POST /v1/payments text/plain, 유효한 키 → 415 UNSUPPORTED_MEDIA_TYPE")
    void unsupportedMediaType(String accept) throws Exception {
      MockHttpServletRequestBuilder request =
          post("/v1/payments").contentType(MediaType.TEXT_PLAIN).content("plain");

      MvcResult result =
          expectError(perform(request, KEY, accept), 415, "UNSUPPORTED_MEDIA_TYPE")
              .andExpect(jsonPath("$.message").value("지원하지 않는 Content-Type 입니다."))
              .andReturn();

      assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT))
          .as("부모가 넣는 지원 미디어 타입 헤더를 보존한다")
          .contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("POST /v1/wallets/1, 유효한 키 → 405 METHOD_NOT_ALLOWED, Allow = {GET}")
    void methodNotAllowed(String accept) throws Exception {
      MvcResult result =
          expectError(
                  perform(post("/v1/wallets/{id}", Seeds.MEMBER_ID_1), KEY, accept),
                  405,
                  "METHOD_NOT_ALLOWED")
              .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."))
              .andReturn();

      assertThat(allow(result)).containsExactly("GET");
    }

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("PUT /v1/payments/confirm, 키 없음 → 405, Allow = {POST, GET} (인증보다 먼저)")
    void methodNotAllowedBeforeAuth(String accept) throws Exception {
      MvcResult result =
          expectError(perform(put("/v1/payments/confirm"), null, accept), 405, "METHOD_NOT_ALLOWED")
              .andReturn();

      assertThat(allow(result)).containsExactlyInAnyOrder("POST", "GET");
    }

    @Test
    @DisplayName("정상 조회에 Accept: text/plain → 정상 응답은 협상을 유지해 406, 그 설명은 JSON")
    void notAcceptableIsExplainedInJson() throws Exception {
      expectError(
              perform(get("/v1/wallets/{id}", Seeds.MEMBER_ID_1), KEY, MediaType.TEXT_PLAIN_VALUE),
              406,
              "NOT_ACCEPTABLE")
          .andExpect(jsonPath("$.message").value("응답할 수 있는 형식이 없습니다."));
    }
  }

  /**
   * 핸들러에 없는 예외.
   *
   * <p>통합 컨텍스트에 예외를 던지는 컨트롤러를 얹으면 컨텍스트 캐시가 갈리므로, advice 만 붙여 컨텍스트 없이 조립한다. 이 방식은 advice 등록 여부와
   * 인터셉터를 검증하지 않는다 — 그 몫은 위 통합 케이스가 맡는다.
   */
  @Nested
  @DisplayName("500 - 예상 밖 오류")
  @ExtendWith(OutputCaptureExtension.class)
  class Unexpected {

    private final MockMvc standalone =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @ParameterizedTest(name = "Accept={0}")
    @NullSource
    @ValueSource(strings = MediaType.TEXT_PLAIN_VALUE)
    @DisplayName("500 INTERNAL_SERVER_ERROR, 고정 문구, 내부 예외 메시지는 나가지 않는다")
    void unhandledException(String accept) throws Exception {
      MockHttpServletRequestBuilder request = get("/boom");
      if (accept != null) {
        request.header(HttpHeaders.ACCEPT, accept);
      }

      String body =
          expectError(standalone.perform(request), 500, "INTERNAL_SERVER_ERROR")
              .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body).doesNotContain(ThrowingController.SECRET);
    }

    @Test
    @DisplayName("핸들러에 없는 예외는 원인과 스택을 ERROR 로그로 한 번 남긴다")
    void unhandledExceptionIsLogged(CapturedOutput output) throws Exception {
      standalone.perform(get("/boom")).andExpect(status().isInternalServerError());

      assertLoggedOnce(output, "java.lang.IllegalStateException: " + ThrowingController.SECRET);
    }

    /**
     * Spring MVC 가 500 으로 판정하는 예외는 catch-all 이 아니라 부모의 구체 핸들러가 받는다. 부모는 ERROR 로그를 남기지 않으므로 여기서 빠지면
     * 클라이언트에 고정 문구만 가고 서버에는 아무 근거도 남지 않는다.
     */
    @Test
    @DisplayName("Spring MVC 가 판정한 500 도 같은 본문이고 원인을 ERROR 로그로 한 번 남긴다")
    void mvcInternalErrorIsLogged(CapturedOutput output) throws Exception {
      expectError(standalone.perform(get("/missing-variable")), 500, "INTERNAL_SERVER_ERROR")
          .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));

      assertLoggedOnce(output, "MissingPathVariableException");
    }

    private void assertLoggedOnce(CapturedOutput output, String cause) {
      String logged = output.getAll();
      assertThat(logged.lines().filter(line -> line.contains(" ERROR ")).count())
          .as("ERROR 로그는 요청당 한 번")
          .isEqualTo(1);
      assertThat(logged).as("원인 예외가 로그에 남는다").contains(cause);
      assertThat(logged).as("스택 트레이스가 로그에 남는다").contains("\tat ");
    }

    @Test
    @DisplayName("예외를 던지는 컨트롤러는 통합 컨텍스트에 등록되지 않는다")
    void throwingControllerIsNotScanned() throws Exception {
      perform(get("/boom"), null, null).andExpect(status().isNotFound());
    }
  }

  /**
   * 핸들러에 없는 예외를 던진다.
   *
   * <p>standalone 설정도 {@code @Controller} 가 있어야 매핑을 등록한다. 대신 {@code @TestComponent} 를 붙여 통합 테스트의
   * 컴포넌트 스캔에서 제외한다 — 스캔은 테스트 클래스패스까지 훑으므로, 빠지지 않으면 실제 컨텍스트에 {@code /boom} 이 생긴다.
   */
  @TestComponent
  @RestController
  static class ThrowingController {

    static final String SECRET = "internal-detail-must-not-leak";

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException(SECRET);
    }

    /** 경로에 없는 변수를 요구한다. Spring MVC 가 {@code MissingPathVariableException} 으로 500 을 판정한다. */
    @GetMapping("/missing-variable")
    String missingVariable(@PathVariable String id) {
      return id;
    }
  }

  private static ResultActions expectError(ResultActions actions, int status, String code)
      throws Exception {
    return actions
        .andExpect(status().is(status))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.message").isString());
  }

  private ResultActions perform(MockHttpServletRequestBuilder builder, String apiKey, String accept)
      throws Exception {
    if (apiKey != null) {
      builder.header(API_KEY_HEADER, apiKey);
    }
    if (accept != null) {
      builder.header(HttpHeaders.ACCEPT, accept);
    }
    return mockMvc.perform(builder);
  }

  private static MockHttpServletRequestBuilder jsonBody(String body) {
    return post("/v1/payments").contentType(MediaType.APPLICATION_JSON).content(body);
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
