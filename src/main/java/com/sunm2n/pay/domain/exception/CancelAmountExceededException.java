package com.sunm2n.pay.domain.exception;

/** 취소 요청 금액이 잔여 금액을 넘는다. */
public class CancelAmountExceededException extends DomainException {

  public CancelAmountExceededException(String paymentKey, long balanceAmount, long cancelAmount) {
    super(
        "취소 금액이 잔여 금액을 초과합니다. paymentKey="
            + paymentKey
            + ", balanceAmount="
            + balanceAmount
            + ", cancelAmount="
            + cancelAmount);
  }
}
