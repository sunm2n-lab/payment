package com.sunm2n.pay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sunm2n.pay.merchant.api.auth.MerchantAuthInterceptor;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 내장 서버에서의 {@code Accept} 결함 확인.
 *
 * <p>규약 이전에 "없는 결제 조회 + {@code Accept: text/plain}" 이 <b>500, 본문 없음</b> 으로 나간 것은 MockMvc 가 아니라 내장
 * Tomcat 에서 실측됐다 (MockMvc 에서는 예외가 DispatcherServlet 밖으로 탈출할 뿐 상태를 볼 수 없다). 같은 경로로 닫는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponseServerTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  @DisplayName("없는 결제 조회 + Accept: text/plain 은 500 이 아니라 404 + JSON 이다")
  void notFoundSurvivesNonJsonAccept() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(MerchantAuthInterceptor.API_KEY_HEADER, Seeds.MERCHANT_1_API_KEY);
    headers.setAccept(List.of(MediaType.TEXT_PLAIN));

    ResponseEntity<String> response =
        restTemplate.exchange(
            "/v1/payments/{key}",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class,
            "no-such-key");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat((String) JsonPath.read(response.getBody(), "$.code")).isEqualTo("NOT_FOUND");
  }
}
