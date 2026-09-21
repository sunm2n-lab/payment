package com.sunm2n.payment.domain.exception;

import com.sunm2n.payment.domain.PaymentStatus;

/** 현재 상태에서 허용되지 않는 전이다. */
public class InvalidPaymentStatusException extends DomainException {

  public InvalidPaymentStatusException(String paymentKey, PaymentStatus current, String operation) {
    super("현재 상태에서는 " + operation + " 할 수 없습니다. paymentKey=" + paymentKey + ", status=" + current);
  }
}
