package com.sunm2n.pay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.merchant.api.auth.MerchantAuthInterceptor;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.support.StateSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * S6 회귀 12 — 인코딩된 주문 id 경로를 내장 Tomcat 이 어떻게 넘기는지 실측한 그대로 고정한다 ({@code docs/plan/S6.md} 4.1).
 *
 * <p>MockMvc 는 서블릿 컨테이너 없이 MVC 를 부르므로 컨테이너의 디코딩·거절을 보여 주지 못한다. {@code
 * IdempotencyKeyHeaderServerTest} 와 같이 <b>소켓에 요청 바이트를 직접 쓴다.</b> 클라이언트 라이브러리가 경로를 다시 인코딩하지 않게 하기
 * 위해서다.
 *
 * <ul>
 *   <li>{@code %2F}(슬래시)·{@code %00} — Tomcat 이 MVC 에 넘기기 전에 400 으로 거절한다. 본문은 Tomcat 의 HTML 이다
 *   <li>{@code %20}·{@code %C3%A9}·{@code %2E}·{@code %3B} — 디코딩되어 컨트롤러까지 오고, 형식 규칙이 400 {@code
 *       INVALID_REQUEST} 로 거절한다
 *   <li>{@code ;x=1}(인코딩하지 않은 경로 파라미터) — Tomcat 이 떼어 내고 남은 값으로 매핑된다. 남은 값이 규칙 안이면 그 주문을 처리한다
 * </ul>
 *
 * <p>어느 쪽이든 규칙 밖의 값으로는 조회·취소가 실행되지 않는다. 부작용 스냅샷으로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderPathServerTest extends AbstractIntegrationTest {

  private static final String ORDER_ID = "order-s6p-1";
  private static final long AMOUNT = 10_000L;
  private static final String CANCEL_BODY = "{\"cancelAmount\":1000}";

  @LocalServerPort private int port;

  @Autowired private PaymentService paymentService;

  @BeforeEach
  void confirmedCardPayment() {
    Long merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    String paymentKey =
        paymentService
            .create(merchantId, ORDER_ID, AMOUNT, PaymentMethod.CARD, null)
            .getPaymentKey();
    paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"order%2Fs6p-1", "..%2Forder-s6p-1", "order-s6p-1%00"})
  @DisplayName("회귀 12 - 인코딩된 슬래시·NUL 은 Tomcat 이 MVC 에 넘기기 전에 400 으로 거절한다")
  void containerRejectsBeforeMvc(String encoded) throws IOException {
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    for (String response : bothRoutes(encoded)) {
      assertThat(statusLine(response)).contains(" 400 ");
      assertThat(response).as("우리 오류 형식이 아니다 - MVC 에 닿지 않았다").doesNotContain("\"code\"");
    }
    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "order%20s6p",
        "caf%C3%A9-order",
        "order-s6p-1%20",
        "order%2Es6p",
        "order-s6p-1%3Bx"
      })
  @DisplayName("회귀 12 - 디코딩되어 컨트롤러에 온 규칙 밖의 값은 400 INVALID_REQUEST 이고 조회·취소하지 않는다")
  void decodedValueIsRejectedByTheRule(String encoded) throws IOException {
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    for (String response : bothRoutes(encoded)) {
      assertThat(statusLine(response)).contains(" 400 ");
      assertThat(response).contains("\"code\":\"INVALID_REQUEST\"");
    }
    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
  }

  @Test
  @DisplayName("회귀 12 - 인코딩하지 않은 경로 파라미터(;x=1)는 Tomcat 이 떼어 내고, 남은 주문 id 로 처리된다")
  void pathParameterIsStrippedByTheContainer() throws IOException {
    String get = send("GET", "/v1/payments/orders/" + ORDER_ID + ";x=1", null);
    String cancel = send("POST", "/v1/payments/orders/" + ORDER_ID + ";x=1/cancel", CANCEL_BODY);

    assertThat(statusLine(get)).contains(" 200 ");
    assertThat(get).contains("\"orderId\":\"" + ORDER_ID + "\"");
    assertThat(statusLine(cancel)).contains(" 200 ");
    assertThat(cancel).contains("\"balanceAmount\":9000");
  }

  private String[] bothRoutes(String encoded) throws IOException {
    return new String[] {
      send("GET", "/v1/payments/orders/" + encoded, null),
      send("POST", "/v1/payments/orders/" + encoded + "/cancel", CANCEL_BODY)
    };
  }

  /** 요청 바이트를 그대로 쓰고 응답 전체를 읽는다. {@code Connection: close} 라 서버가 닫을 때까지 읽으면 된다. */
  private String send(String method, String path, String body) throws IOException {
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    StringBuilder request =
        new StringBuilder()
            .append(method)
            .append(' ')
            .append(path)
            .append(" HTTP/1.1\r\n")
            .append("Host: localhost:")
            .append(port)
            .append("\r\n")
            .append(MerchantAuthInterceptor.API_KEY_HEADER)
            .append(": ")
            .append(Seeds.MERCHANT_1_API_KEY)
            .append("\r\n");
    if (body != null) {
      request
          .append("Content-Type: application/json\r\n")
          .append("Content-Length: ")
          .append(bytes.length)
          .append("\r\n");
    }
    request.append("Connection: close\r\n\r\n");

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.write(bytes);
      out.flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      in.transferTo(response);
      return response.toString(StandardCharsets.UTF_8);
    }
  }

  private static String statusLine(String response) {
    return response.substring(0, response.indexOf("\r\n"));
  }
}
