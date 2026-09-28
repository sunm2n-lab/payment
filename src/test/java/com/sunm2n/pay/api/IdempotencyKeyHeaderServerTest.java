package com.sunm2n.pay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.merchant.api.auth.MerchantAuthInterceptor;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 내장 Tomcat 이 {@code Idempotency-Key} 헤더를 컨트롤러에 어떻게 넘기는지 확인한다 ({@code docs/plan/S5.md} 4.1).
 *
 * <p>MockMvc 는 서블릿 컨테이너를 거치지 않아 헤더 값을 준 그대로 넘긴다. JDK {@code HttpClient} 나 {@code RestTemplate} 도
 * 보내기 전에 헤더를 검사·정규화할 수 있다. 그래서 <b>소켓에 요청 바이트를 직접 쓴다.</b>
 *
 * <ul>
 *   <li>값 앞뒤의 공백 — 컨테이너가 자르는가
 *   <li>같은 헤더 두 줄 — {@code String} 바인딩에서 쉼표로 합쳐져 400 이 되는가
 *   <li>빈 값 — 헤더 없음과 구분되어 400 이 되는가
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyKeyHeaderServerTest extends AbstractIntegrationTest {

  private static final long AMOUNT = 10_000L;
  private static final String BODY = "{\"cancelAmount\":3000,\"reason\":\"재전송\"}";

  @LocalServerPort private int port;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  private String paymentKey;

  @BeforeEach
  void confirmedMoneyPayment() {
    Long merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    walletService.charge(Seeds.MEMBER_ID_1, 100_000L);
    paymentKey =
        paymentService
            .create(merchantId, "order-s5-h", AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
            .getPaymentKey();
    paymentService.confirm(merchantId, paymentKey, "order-s5-h", AMOUNT);
  }

  @Test
  @DisplayName("값 앞뒤의 공백은 Tomcat 이 잘라서 넘긴다 - 공백을 붙인 키와 붙이지 않은 키는 같은 키다")
  void surroundingWhitespaceIsTrimmedByTheContainer() throws IOException {
    String first = send(List.of("Idempotency-Key:   ws-key \t "));
    String second = send(List.of("Idempotency-Key: ws-key"));

    assertThat(statusLine(first)).contains(" 200 ");
    assertThat(statusLine(second)).contains(" 200 ");
    assertThat(storedKeys()).containsExactly("ws-key");
    assertThat(cancelRows()).as("두 번째는 재생이다").isEqualTo(1);
  }

  @Test
  @DisplayName("같은 헤더 두 줄은 쉼표로 합쳐져 400 이다")
  void repeatedHeaderLinesAreJoinedAndRejected() throws IOException {
    String response = send(List.of("Idempotency-Key: key-x", "Idempotency-Key: key-y"));

    assertThat(statusLine(response)).contains(" 400 ");
    assertThat(response).contains("\"code\":\"INVALID_REQUEST\"");
    assertThat(storedKeys()).isEmpty();
    assertThat(cancelRows()).isZero();
  }

  @Test
  @DisplayName("빈 값(Idempotency-Key:)은 헤더 없음과 구분되어 400 이다")
  void emptyValueIsNotAbsence() throws IOException {
    String empty = send(List.of("Idempotency-Key:"));
    String absent = send(List.of());

    assertThat(statusLine(empty)).contains(" 400 ");
    assertThat(statusLine(absent)).contains(" 200 ");
    assertThat(storedKeys()).isEmpty();
    assertThat(cancelRows()).isEqualTo(1);
  }

  /** 요청 바이트를 그대로 쓰고 응답 전체를 읽는다. {@code Connection: close} 라 서버가 닫을 때까지 읽으면 된다. */
  private String send(List<String> extraHeaderLines) throws IOException {
    byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
    StringBuilder request =
        new StringBuilder()
            .append("POST /v1/payments/")
            .append(paymentKey)
            .append("/cancel HTTP/1.1\r\n")
            .append("Host: localhost:")
            .append(port)
            .append("\r\n")
            .append(MerchantAuthInterceptor.API_KEY_HEADER)
            .append(": ")
            .append(Seeds.MERCHANT_1_API_KEY)
            .append("\r\n")
            .append("Content-Type: application/json\r\n")
            .append("Content-Length: ")
            .append(body.length)
            .append("\r\n");
    extraHeaderLines.forEach(line -> request.append(line).append("\r\n"));
    request.append("Connection: close\r\n\r\n");

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(10_000);
      OutputStream out = socket.getOutputStream();
      out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.write(body);
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

  private List<String> storedKeys() {
    return jdbcTemplate.queryForList(
        "SELECT idempotency_key FROM idempotency_key ORDER BY id", String.class);
  }

  private int cancelRows() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_cancel", Integer.class);
  }
}
