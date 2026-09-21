package com.sunm2n.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sunm2n.payment.support.AbstractApiTest;
import com.sunm2n.payment.support.Seeds;
import com.sunm2n.payment.support.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * 실패 규약.
 *
 * <p>4xx 매핑은 인터셉터와 {@code ApiExceptionHandler} 가 결정하므로 서비스 테스트로는 검증되지 않는다. 이후 모든 시나리오의 완료 기준이 "예상한
 * 실패 응답과 예상 밖 오류를 구분했는지"(SCENARIO 114행)에 의존하므로, 실제 상태 코드를 여기서 고정한다.
 */
class FailureContractApiTest extends AbstractApiTest {

  private static final String KEY = Seeds.MERCHANT_1_API_KEY;
  private static final String OTHER_KEY = Seeds.MERCHANT_2_API_KEY;
  private static final long AMOUNT = 10_000L;

  @Nested
  @DisplayName("401 - 인증")
  class Unauthorized {

    @Test
    @DisplayName("API 키가 없으면 401")
    void missingApiKey() throws Exception {
      createPayment(null, createBody("no-key", AMOUNT, "CARD"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("등록되지 않은 API 키면 401")
    void unknownApiKey() throws Exception {
      createPayment("mk_not_registered", createBody("bad-key", AMOUNT, "CARD"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("조회에도 API 키가 필요하다")
    void getRequiresApiKey() throws Exception {
      getWallet(null, Seeds.MEMBER_ID_1).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("404 - 미존재와 소유권")
  class NotFound {

    @Test
    @DisplayName("없는 paymentKey 조회는 404")
    void unknownPaymentKey() throws Exception {
      getPayment(KEY, "no-such-key")
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 지갑 조회는 404")
    void unknownWallet() throws Exception {
      getWallet(KEY, 9_999L).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 가맹점의 결제는 조회·승인·취소 모두 404 - 존재를 노출하지 않는다")
    void otherMerchantsPayment() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("owned", AMOUNT, "CARD"));
      confirmPayment(KEY, confirmBody(paymentKey, "owned", AMOUNT)).andExpect(status().isOk());

      getPayment(OTHER_KEY, paymentKey).andExpect(status().isNotFound());
      confirmPayment(OTHER_KEY, confirmBody(paymentKey, "owned", AMOUNT))
          .andExpect(status().isNotFound());
      cancelPayment(OTHER_KEY, paymentKey, cancelBody(1_000L)).andExpect(status().isNotFound());

      // 없는 키와 남의 결제가 같은 응답이어야 존재가 드러나지 않는다
      getPayment(OTHER_KEY, "no-such-key").andExpect(status().isNotFound());
    }
  }

  @Nested
  @DisplayName("400 - 요청 검증")
  class BadRequest {

    @Test
    @DisplayName("금액이 0 이면 400")
    void zeroAmount() throws Exception {
      createPayment(KEY, createBody("zero", 0L, "CARD"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("금액이 음수면 400")
    void negativeAmount() throws Exception {
      createPayment(KEY, createBody("negative", -1L, "CARD")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("MONEY 인데 memberId 가 없으면 400")
    void moneyWithoutMemberId() throws Exception {
      createPayment(KEY, createBody("money-no-member", AMOUNT, "MONEY"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("충전 금액이 0 이면 400")
    void zeroCharge() throws Exception {
      chargeWallet(KEY, Seeds.MEMBER_ID_1, amountBody(0L)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("취소 금액이 0 이면 400")
    void zeroCancelAmount() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("zero-cancel", AMOUNT, "CARD"));
      confirmPayment(KEY, confirmBody(paymentKey, "zero-cancel", AMOUNT))
          .andExpect(status().isOk());

      cancelPayment(KEY, paymentKey, cancelBody(0L)).andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("409 - 업무 규칙 위반 (거절 후 부작용 없음까지 확인)")
  class Conflict {

    @Test
    @DisplayName("잔액이 부족하면 409 이고 아무것도 바뀌지 않는다")
    void insufficientBalance() throws Exception {
      chargeWallet(KEY, Seeds.MEMBER_ID_1, amountBody(5_000L)).andExpect(status().isOk());
      String paymentKey = createPaymentKey(KEY, createMoneyBody("poor", AMOUNT, Seeds.MEMBER_ID_1));

      assertRejectedWithoutSideEffect(
          () -> confirmPayment(KEY, confirmBody(paymentKey, "poor", AMOUNT)),
          status().isConflict(),
          "INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("orderId 가 다르면 409 이고 카드사 승인 횟수가 늘지 않는다")
    void orderIdMismatch() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("mismatch-1", AMOUNT, "CARD"));

      assertRejectedWithoutSideEffect(
          () -> confirmPayment(KEY, confirmBody(paymentKey, "other-order", AMOUNT)),
          status().isConflict(),
          "PAYMENT_MISMATCH");
    }

    @Test
    @DisplayName("amount 가 다르면 409 이고 카드사 승인 횟수가 늘지 않는다")
    void amountMismatch() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("mismatch-2", AMOUNT, "CARD"));

      assertRejectedWithoutSideEffect(
          () -> confirmPayment(KEY, confirmBody(paymentKey, "mismatch-2", 9_999L)),
          status().isConflict(),
          "PAYMENT_MISMATCH");
    }

    @Test
    @DisplayName("READY 가 아닌 결제의 승인은 409 - 준비 단계의 승인 1회에서 더 늘지 않는다")
    void confirmAlreadyDone() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("already", AMOUNT, "CARD"));
      confirmPayment(KEY, confirmBody(paymentKey, "already", AMOUNT)).andExpect(status().isOk());
      // 준비 단계에서 이미 한 번 승인했으므로 "호출 0" 이 아니라 "증가 없음" 으로 본다
      assertThat(fakeCardApprovalClient.getApproveCount()).isEqualTo(1);

      assertRejectedWithoutSideEffect(
          () -> confirmPayment(KEY, confirmBody(paymentKey, "already", AMOUNT)),
          status().isConflict(),
          "INVALID_PAYMENT_STATUS");
    }

    @Test
    @DisplayName("READY 결제의 취소는 409")
    void cancelReadyPayment() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("ready-cancel", AMOUNT, "CARD"));

      assertRejectedWithoutSideEffect(
          () -> cancelPayment(KEY, paymentKey, cancelBody(1_000L)),
          status().isConflict(),
          "INVALID_PAYMENT_STATUS");
    }

    @Test
    @DisplayName("잔여 금액을 넘는 취소는 409 - 앞선 취소 1회에서 더 늘지 않는다")
    void cancelBeyondBalanceAmount() throws Exception {
      String paymentKey = createPaymentKey(KEY, createBody("over-cancel", AMOUNT, "CARD"));
      confirmPayment(KEY, confirmBody(paymentKey, "over-cancel", AMOUNT))
          .andExpect(status().isOk());
      cancelPayment(KEY, paymentKey, cancelBody(3_000L)).andExpect(status().isOk());

      assertRejectedWithoutSideEffect(
          () -> cancelPayment(KEY, paymentKey, cancelBody(7_001L)),
          status().isConflict(),
          "CANCEL_AMOUNT_EXCEEDED");
    }
  }

  /** 거절 요청의 **직전과 직후**를 비교해 잔액·원장·취소 이력·결제 상태가 그대로이고, Fake 카드사 호출 횟수가 증가하지 않았는지 확인한다. */
  private void assertRejectedWithoutSideEffect(
      Rejection rejection, ResultMatcher expectedStatus, String expectedCode) throws Exception {
    StateSnapshot before = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);

    rejection.perform().andExpect(expectedStatus).andExpect(jsonPath("$.code").value(expectedCode));

    StateSnapshot after = StateSnapshot.capture(jdbcTemplate, fakeCardApprovalClient);
    assertThat(after).as("거절된 요청은 아무 상태도 바꾸지 않아야 한다").isEqualTo(before);
    invariants.assertAll();
  }

  @FunctionalInterface
  private interface Rejection {
    ResultActions perform() throws Exception;
  }
}
