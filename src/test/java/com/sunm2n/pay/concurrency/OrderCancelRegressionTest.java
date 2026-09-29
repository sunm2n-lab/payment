package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.support.StateSnapshot;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S6 회귀 — 주문 기반 조회·취소의 순차 동작 ({@code docs/plan/S6.md} 6절 회귀 1~5, 9, 12 의 MVC 판정).
 *
 * <p>HTTP 로 확인한다. 경로 변수 검증, 404·409 매핑, "{@code paymentKey} 경로와 같은 결과" 는 컨트롤러와 예외 핸들러를 지나야 드러난다. 이
 * 커밋 시점의 최신 스키마는 V5(인덱스 없음)다. 순차 동작은 스키마와 무관하게 같아야 한다.
 */
class OrderCancelRegressionTest extends AbstractApiTest {

  private static final String M1 = Seeds.MERCHANT_1_API_KEY;
  private static final String M2 = Seeds.MERCHANT_2_API_KEY;
  private static final long AMOUNT = 10_000L;

  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void invariantsHold() {
    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 1 - 주문으로 조회하면 200 이고 paymentKey 조회와 본문이 같다")
  void getByOrderMatchesGetByPaymentKey() throws Exception {
    String paymentKey = confirmedCardPayment(M1, "order-s6r-1");

    String byOrder = body(getPaymentByOrder(M1, "order-s6r-1").andExpect(status().isOk()));
    String byKey = body(getPayment(M1, paymentKey).andExpect(status().isOk()));

    assertThat(json(byOrder)).isEqualTo(json(byKey));
  }

  @Test
  @DisplayName("회귀 2 - 주문으로 부분취소 → 전액취소는 paymentKey 취소와 같은 결과다")
  void cancelByOrderMatchesCancelByPaymentKey() throws Exception {
    String viaKey = confirmedCardPayment(M1, "order-s6r-2a");
    confirmedCardPayment(M1, "order-s6r-2b");

    String keyPartial =
        body(cancelPayment(M1, viaKey, cancelBody(3_000L)).andExpect(status().isOk()));
    String keyFull = body(cancelPayment(M1, viaKey, cancelBody(7_000L)).andExpect(status().isOk()));
    int cardCancelsViaKey = fakeCardApprovalClient.getCancelCount();
    String orderPartial =
        body(
            cancelPaymentByOrder(M1, "order-s6r-2b", cancelBody(3_000L))
                .andExpect(status().isOk()));
    String orderFull =
        body(
            cancelPaymentByOrder(M1, "order-s6r-2b", cancelBody(7_000L))
                .andExpect(status().isOk()));

    assertThat(withoutIdentity(orderPartial)).isEqualTo(withoutIdentity(keyPartial));
    assertThat(withoutIdentity(orderFull)).isEqualTo(withoutIdentity(keyFull));
    assertThat(json(orderPartial).get("status").asText()).isEqualTo("PARTIAL_CANCELED");
    assertThat(json(orderFull).get("status").asText()).isEqualTo("CANCELED");
    assertThat(cancelAmountsOf("order-s6r-2b")).isEqualTo(cancelAmountsOf("order-s6r-2a"));
    assertThat(fakeCardApprovalClient.getCancelCount() - cardCancelsViaKey)
        .as("카드사 취소 호출 수")
        .isEqualTo(cardCancelsViaKey)
        .isEqualTo(2);
  }

  @Test
  @DisplayName("회귀 3 - 없는 주문과 다른 가맹점의 주문은 조회·취소 모두 404 이고 부작용이 없다")
  void unknownOrUnownedOrderIsNotFound() throws Exception {
    confirmedCardPayment(M1, "order-s6r-3");
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    expectNotFound(getPaymentByOrder(M1, "order-s6r-none"));
    expectNotFound(cancelPaymentByOrder(M1, "order-s6r-none", cancelBody(1_000L)));
    expectNotFound(getPaymentByOrder(M2, "order-s6r-3"));
    expectNotFound(cancelPaymentByOrder(M2, "order-s6r-3", cancelBody(1_000L)));
    expectNotFound(cancelPaymentByOrderWithKey(M2, "order-s6r-3", cancelBody(1_000L), "k-3"));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
    assertThat(keyRowCount()).as("업무 실패는 키도 남기지 않는다").isZero();
  }

  @Test
  @DisplayName("회귀 3 - 없는 주문과 남의 주문은 같은 응답이다 - 존재가 드러나지 않는다")
  void unknownAndUnownedLookAlike() throws Exception {
    confirmedCardPayment(M1, "order-s6r-3b");
    confirmedCardPayment(M2, "order-s6r-3c");

    String unowned = body(getPaymentByOrder(M2, "order-s6r-3b"));
    String unknown = body(getPaymentByOrder(M2, "order-s6r-3z"));

    assertThat(unowned.replace("order-s6r-3b", "ID"))
        .isEqualTo(unknown.replace("order-s6r-3z", "ID"));
  }

  @Test
  @DisplayName("회귀 4 - 주문 기반 취소에 같은 멱등키를 다시 보내면 취소 1건, 저장된 응답")
  void resendWithSameKeyReplays() throws Exception {
    confirmedCardPayment(M1, "order-s6r-4");

    String first =
        body(
            cancelPaymentByOrderWithKey(M1, "order-s6r-4", cancelBody(3_000L), "order-key-4")
                .andExpect(status().isOk()));
    String second =
        body(
            cancelPaymentByOrderWithKey(M1, "order-s6r-4", cancelBody(3_000L), "order-key-4")
                .andExpect(status().isOk()));

    assertThat(json(second)).isEqualTo(json(first));
    assertThat(cancelAmountsOf("order-s6r-4")).containsExactly(3_000L);
    assertThat(fakeCardApprovalClient.getCancelCount()).isEqualTo(1);
    assertThat(keyRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 5 - 같은 멱등키를 paymentKey 취소와 주문 기반 취소에 쓰면 409 IDEMPOTENCY_KEY_REUSED")
  void sameKeyAcrossCancelPathsIsReused() throws Exception {
    String paymentKey = confirmedCardPayment(M1, "order-s6r-5");
    cancelPaymentWithKey(M1, paymentKey, cancelBody(3_000L), "shared-key")
        .andExpect(status().isOk());
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    cancelPaymentByOrderWithKey(M1, "order-s6r-5", cancelBody(3_000L), "shared-key")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
  }

  @Test
  @DisplayName("회귀 5 - 반대 순서(주문 기반 → paymentKey)도 409 다")
  void sameKeyAcrossCancelPathsIsReusedReversed() throws Exception {
    String paymentKey = confirmedCardPayment(M1, "order-s6r-5b");
    cancelPaymentByOrderWithKey(M1, "order-s6r-5b", cancelBody(3_000L), "shared-key-b")
        .andExpect(status().isOk());

    cancelPaymentWithKey(M1, paymentKey, cancelBody(3_000L), "shared-key-b")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    assertThat(cancelAmountsOf("order-s6r-5b")).containsExactly(3_000L);
  }

  @Test
  @DisplayName("회귀 9 - MONEY 결제를 주문으로 취소하면 환불 원장 1건이고 잔액 == 원장 합계다")
  void moneyCancelByOrderRefunds() throws Exception {
    chargeWallet(M1, Seeds.MEMBER_ID_1, amountBody(50_000L)).andExpect(status().isOk());
    String paymentKey =
        createPaymentKey(M1, createMoneyBody("order-s6r-9", AMOUNT, Seeds.MEMBER_ID_1));
    confirmPayment(M1, confirmBody(paymentKey, "order-s6r-9", AMOUNT)).andExpect(status().isOk());

    cancelPaymentByOrder(M1, "order-s6r-9", cancelBody(4_000L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanceAmount").value(6_000L));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'REFUND' AND amount = 4000",
                Integer.class))
        .isEqualTo(1);
    getWallet(M1, Seeds.MEMBER_ID_1).andExpect(jsonPath("$.balance").value(44_000L));
  }

  /**
   * 형식 밖의 값을 경로로 보내면 조회·취소가 실행되지 않는다. 규칙 이전에 저장된 주문(예: 5자)이 있어도 그 결제를 고르지 않는다 — DB 에 닿기 전에 400 이다.
   * 인코딩된 경로가 컨테이너를 어떻게 지나는지는 {@code OrderPathServerTest} 가 본다.
   */
  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @ValueSource(strings = {"abcde", "legacy.1", "legacy~1"})
  @DisplayName("회귀 12 - 형식 밖의 주문 id 는 경로로 와도 400 이고, 같은 값의 옛 결제가 있어도 조회·취소하지 않는다")
  void outsideRuleInPathIsRejectedBeforeLookup(String orderId) throws Exception {
    String paymentKey = confirmedCardPayment(M1, "order-s6r-12");
    // 규칙 이전에 저장된 주문을 흉내 낸다.
    jdbcTemplate.update(
        "UPDATE payment SET order_id = ? WHERE payment_key = ?", orderId, paymentKey);
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    expectInvalid(getPaymentByOrder(M1, orderId));
    expectInvalid(cancelPaymentByOrder(M1, orderId, cancelBody(1_000L)));
    expectInvalid(cancelPaymentByOrderWithKey(M1, orderId, cancelBody(1_000L), "k-12"));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
    assertThat(keyRowCount()).isZero();
  }

  private String confirmedCardPayment(String apiKey, String orderId) throws Exception {
    String paymentKey = createPaymentKey(apiKey, createBody(orderId, AMOUNT, "CARD"));
    confirmPayment(apiKey, confirmBody(paymentKey, orderId, AMOUNT)).andExpect(status().isOk());
    return paymentKey;
  }

  private static void expectNotFound(ResultActions result) throws Exception {
    result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  private static void expectInvalid(ResultActions result) throws Exception {
    result
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private JsonNode json(String body) throws Exception {
    return objectMapper.readTree(body);
  }

  /** 결제마다 다른 식별 필드를 지운 본문. 금액·상태·승인 시각의 존재만 비교한다. */
  private JsonNode withoutIdentity(String body) throws Exception {
    ObjectNode node = (ObjectNode) json(body);
    node.remove("paymentKey");
    node.remove("orderId");
    node.remove("cardApprovalNo");
    node.put("approvedAt", node.hasNonNull("approvedAt"));
    return node;
  }

  private List<Long> cancelAmountsOf(String orderId) {
    return jdbcTemplate.queryForList(
        "SELECT c.cancel_amount FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.order_id = ? ORDER BY c.id",
        Long.class,
        orderId);
  }

  private int keyRowCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_key", Integer.class);
  }
}
