package com.sunm2n.payment.api.dto;

import com.sunm2n.payment.domain.PaymentMethod;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 요청 금액이 양수인지는 API 경계에서 Bean Validation 으로 검증한다 (SCENARIO 42행). */
public record CreatePaymentRequest(
    @NotBlank @Size(max = 64) String orderId,
    @Positive long amount,
    @NotNull PaymentMethod method,
    Long memberId) {

  @AssertTrue(message = "MONEY 결제에는 memberId 가 필요합니다")
  public boolean isMemberIdPresentForMoney() {
    return method != PaymentMethod.MONEY || memberId != null;
  }
}
