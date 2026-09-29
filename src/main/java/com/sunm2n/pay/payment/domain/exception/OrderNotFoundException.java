package com.sunm2n.pay.payment.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/**
 * 주문 id 로 결제를 찾을 수 없다 — 404 {@code NOT_FOUND} ({@code docs/plan/S6.md} 4.1).
 *
 * <p>다른 가맹점의 주문도 이 예외다. {@code merchant_id} 가 SQL 조건에 들어가므로 남의 주문은 결과 자체가 없다. {@link
 * PaymentNotFoundException} 처럼 메모리에서 소유를 비교하지 않는다.
 */
public class OrderNotFoundException extends DomainException {

  public OrderNotFoundException(String orderId) {
    super("결제를 찾을 수 없습니다. orderId=" + orderId);
  }
}
