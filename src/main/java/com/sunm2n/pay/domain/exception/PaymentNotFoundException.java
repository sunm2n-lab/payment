package com.sunm2n.pay.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/**
 * 결제를 찾을 수 없다.
 *
 * <p>요청한 가맹점의 결제가 아닌 경우에도 이 예외를 쓴다. 다른 가맹점 결제의 존재를 노출하지 않기 위해서다.
 */
public class PaymentNotFoundException extends DomainException {

  public PaymentNotFoundException(String paymentKey) {
    super("결제를 찾을 수 없습니다. paymentKey=" + paymentKey);
  }
}
