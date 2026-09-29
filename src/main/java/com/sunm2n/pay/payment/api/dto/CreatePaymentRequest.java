package com.sunm2n.pay.payment.api.dto;

import com.sunm2n.pay.payment.domain.OrderIds;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 요청 금액이 양수인지는 API 경계에서 Bean Validation 으로 검증한다 (SCENARIO 42행).
 *
 * <p>{@code orderId} 는 {@link OrderIds} 규칙을 따른다. 컬럼 길이(64)도 이 규칙 안에 있다. S6 부터 주문 id 가 경로 변수와 unique
 * 키로 쓰이므로 형식을 입력 시점에 제한한다 ({@code docs/plan/S6.md} 1.2).
 */
public record CreatePaymentRequest(
    @NotNull @Pattern(regexp = OrderIds.REGEX, message = OrderIds.RULE) String orderId,
    @Positive long amount,
    @NotNull PaymentMethod method,
    Long memberId) {

  @AssertTrue(message = "MONEY 결제에는 memberId 가 필요합니다")
  public boolean isMemberIdPresentForMoney() {
    return method != PaymentMethod.MONEY || memberId != null;
  }
}
