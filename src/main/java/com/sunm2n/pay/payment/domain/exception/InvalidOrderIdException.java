package com.sunm2n.pay.payment.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;
import com.sunm2n.pay.payment.domain.OrderIds;

/** 경로 변수로 받은 주문 id 가 {@link OrderIds} 규칙 밖이다 — 400 {@code INVALID_REQUEST}. 조회·취소를 실행하지 않는다. */
public class InvalidOrderIdException extends DomainException {

  public InvalidOrderIdException() {
    super("orderId: " + OrderIds.RULE);
  }
}
