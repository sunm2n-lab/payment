package com.sunm2n.pay.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/** 승인 요청의 orderId 또는 amount 가 생성 시점의 값과 다르다 (SCENARIO 의 3값 일치 검증). */
public class PaymentMismatchException extends DomainException {

  public PaymentMismatchException(String paymentKey, String field) {
    super("승인 요청 값이 결제와 일치하지 않습니다. paymentKey=" + paymentKey + ", field=" + field);
  }
}
