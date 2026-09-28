package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.support.StateSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * S5 회귀 — 본선(unique + insert-first)의 순차 재전송과 키 규칙 ({@code docs/plan/S5.md} 6절 회귀 1~6, 11~13).
 *
 * <p>HTTP 로 확인한다. 헤더 해석, 409·400 매핑, "두 응답이 같다" 는 모두 컨트롤러와 예외 핸들러를 지나야 드러난다. 트랜잭션 계약(14)은 {@code
 * JdbcIdempotencyKeyStoreTest} 에 있다.
 *
 * <p>공통 준비: 충전 100,000 → MONEY 결제 10,000 승인. 모든 사례 뒤에 공통 불변식을 확인한다.
 */
class IdempotentCancelRegressionTest extends AbstractApiTest {

  private static final String M1 = Seeds.MERCHANT_1_API_KEY;
  private static final long CHARGE = 100_000L;
  private static final long AMOUNT = 10_000L;
  private static final long CANCEL = 3_000L;
  private static final String KEY = "cancel-key-1";

  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void invariantsHold() {
    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 1 - 같은 키·같은 내용을 순차로 다시 보내면 취소 1건, REFUND 1건, 두 응답이 같다")
  void sequentialResendReplaysTheSameResponse() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-1");

    String first = body(cancel(paymentKey, CANCEL, "재전송", KEY).andExpect(status().isOk()));
    String second = body(cancel(paymentKey, CANCEL, "재전송", KEY).andExpect(status().isOk()));

    assertThat(json(second)).as("HTTP 상태와 JSON 필드·값이 같다 (4.5)").isEqualTo(json(first));
    assertThat(json(first).get("balanceAmount").asLong()).isEqualTo(AMOUNT - CANCEL);
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(refundLedgerCount()).isEqualTo(1);
    assertThat(keyRows()).containsExactly(Map.of("status", "COMPLETED", "response_status", 200));
  }

  @Test
  @DisplayName("회귀 2 - 같은 키에 다른 금액·다른 사유·다른 결제는 409 IDEMPOTENCY_KEY_REUSED 이고 부작용이 없다")
  void differentContentIsRejected() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-2");
    String other = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-2b");
    cancel(paymentKey, CANCEL, "재전송", KEY).andExpect(status().isOk());
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);
    List<Map<String, Object>> keysBefore = keyRows();

    expectReused(cancel(paymentKey, 2_000L, "재전송", KEY));
    expectReused(cancel(paymentKey, CANCEL, "다른 사유", KEY));
    expectReused(cancel(other, CANCEL, "재전송", KEY));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
    assertThat(keyRows()).isEqualTo(keysBefore);
  }

  @Test
  @DisplayName("회귀 3 - reason 이 null 인 요청 뒤 빈 문자열이면 다른 요청이다 (409)")
  void nullAndEmptyReasonAreDifferentRequests() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-3");

    cancel(paymentKey, CANCEL, null, KEY).andExpect(status().isOk());
    expectReused(cancel(paymentKey, CANCEL, "", KEY));

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 4 - 키 A 취소 → 키 B 추가 취소 → A 재전송은 A 당시의 응답(잔여 7,000)이다")
  void replayReturnsTheSnapshotNotTheCurrentState() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-4");

    String first = body(cancel(paymentKey, CANCEL, "A", "key-a").andExpect(status().isOk()));
    cancel(paymentKey, 2_000L, "B", "key-b").andExpect(status().isOk());
    String replayed = body(cancel(paymentKey, CANCEL, "A", "key-a").andExpect(status().isOk()));

    assertThat(json(replayed)).isEqualTo(json(first));
    assertThat(json(replayed).get("balanceAmount").asLong()).isEqualTo(7_000L);
    assertThat(balanceAmountOf(paymentKey)).as("현재 잔여").isEqualTo(5_000L);
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(2);
  }

  @Test
  @DisplayName("회귀 5 - 다른 가맹점이 같은 키 문자열을 써도 각자 처리된다")
  void keyIsScopedByMerchant() throws Exception {
    String mine = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-5a");
    String theirs =
        confirmedMoneyPayment(Seeds.MERCHANT_2_API_KEY, Seeds.MEMBER_ID_2, "order-s5r-5b");

    cancel(mine, CANCEL, "재전송", KEY).andExpect(status().isOk());
    cancelPaymentWithKey(Seeds.MERCHANT_2_API_KEY, theirs, cancelBody(CANCEL, "재전송"), KEY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentKey").value(theirs));

    assertThat(cancelRowsOf(mine)).isEqualTo(1);
    assertThat(cancelRowsOf(theirs)).isEqualTo(1);
    assertThat(keyRows()).hasSize(2);
  }

  @Test
  @DisplayName("회귀 6 - 대소문자만 다른 키는 다른 키로 처리된다")
  void keysAreCaseSensitive() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-6");

    cancel(paymentKey, CANCEL, "재전송", "abc").andExpect(status().isOk());
    cancel(paymentKey, CANCEL, "재전송", "ABC")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanceAmount").value(AMOUNT - 2 * CANCEL));

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(2);
    assertThat(keyRows()).hasSize(2);
  }

  @ParameterizedTest(name = "[{index}] \"{0}\"")
  @ValueSource(
      strings = {
        "",
        "   ",
        "a1234567890123456789012345678901234567890123456789012345678901234",
        "키",
        "tab\tkey",
        "a,b"
      })
  @DisplayName("회귀 11 - 빈 값·공백뿐·65자·비ASCII·제어 문자·쉼표 포함은 400 이고 부작용이 없다")
  void malformedKeyIsRejected(String key) throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-11");
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    cancel(paymentKey, CANCEL, "재전송", key)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
    assertThat(keyRows()).isEmpty();
  }

  @Test
  @DisplayName("회귀 11 - 64자 키는 허용된다 (경계)")
  void sixtyFourCharacterKeyIsAccepted() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-11b");

    cancel(paymentKey, CANCEL, "재전송", "k".repeat(64)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("회귀 11 - 같은 헤더를 두 번 보내면 쉼표로 합쳐져 400 이다")
  void repeatedHeaderIsRejected() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-11c");
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    cancelPaymentWithKey(M1, paymentKey, cancelBody(CANCEL, "재전송"), "key-x", "key-y")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient)).isEqualTo(before);
    assertThat(keyRows()).isEmpty();
  }

  @Test
  @DisplayName("회귀 12 - 키 INSERT 뒤 취소가 업무 실패하면 키 행도 남지 않는다 (같은 트랜잭션)")
  void businessFailureRollsBackTheKey() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-12");

    cancel(paymentKey, 20_000L, "초과", KEY)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CANCEL_AMOUNT_EXCEEDED"));

    assertThat(keyRows()).isEmpty();
    assertThat(cancelRowsOf(paymentKey)).isZero();
  }

  @Test
  @DisplayName("회귀 13 - 업무 실패 뒤 같은 키·같은 내용 재전송은 재생이 아니라 재실행되어 200 이다")
  void resendAfterBusinessFailureRunsAgain() throws Exception {
    chargeWallet(M1, Seeds.MEMBER_ID_1, amountBody(CHARGE)).andExpect(status().isOk());
    String paymentKey =
        createPaymentKey(M1, createMoneyBody("order-s5r-13", AMOUNT, Seeds.MEMBER_ID_1));

    cancel(paymentKey, CANCEL, "재전송", KEY)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS"));
    assertThat(keyRows()).isEmpty();

    confirmPayment(M1, confirmBody(paymentKey, "order-s5r-13", AMOUNT)).andExpect(status().isOk());
    cancel(paymentKey, CANCEL, "재전송", KEY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanceAmount").value(AMOUNT - CANCEL));

    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
  }

  @Test
  @DisplayName("헤더 없는 요청은 기존 경로다 - 키 행을 남기지 않는다")
  void noHeaderKeepsTheExistingPath() throws Exception {
    String paymentKey = confirmedMoneyPayment(M1, Seeds.MEMBER_ID_1, "order-s5r-0");

    cancelPayment(M1, paymentKey, cancelBody(CANCEL)).andExpect(status().isOk());

    assertThat(keyRows()).isEmpty();
  }

  private ResultActions cancel(String paymentKey, long amount, String reason, String key)
      throws Exception {
    return cancelPaymentWithKey(M1, paymentKey, cancelBody(amount, reason), key);
  }

  private void expectReused(ResultActions result) throws Exception {
    result
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  /** reason 이 null 이면 필드를 빼고, 빈 문자열이면 {@code ""} 로 보낸다. */
  private String cancelBody(long amount, String reason) throws Exception {
    return reason == null
        ? cancelBody(amount)
        : "{\"cancelAmount\":%d,\"reason\":%s}"
            .formatted(amount, objectMapper.writeValueAsString(reason));
  }

  private String confirmedMoneyPayment(String apiKey, long memberId, String orderId)
      throws Exception {
    chargeWallet(apiKey, memberId, amountBody(CHARGE)).andExpect(status().isOk());
    String paymentKey = createPaymentKey(apiKey, createMoneyBody(orderId, AMOUNT, memberId));
    confirmPayment(apiKey, confirmBody(paymentKey, orderId, AMOUNT)).andExpect(status().isOk());
    return paymentKey;
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private JsonNode json(String body) throws Exception {
    return objectMapper.readTree(body);
  }

  private List<Map<String, Object>> keyRows() {
    return jdbcTemplate.queryForList(
        "SELECT status, response_status FROM idempotency_key ORDER BY id");
  }

  private int cancelRowsOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }

  private long balanceAmountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT balance_amount FROM payment WHERE payment_key = ?", Long.class, paymentKey);
  }

  private int refundLedgerCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'REFUND'", Integer.class);
  }
}
