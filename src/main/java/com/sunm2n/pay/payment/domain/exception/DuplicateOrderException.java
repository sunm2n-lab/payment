package com.sunm2n.pay.payment.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/**
 * 같은 가맹점에 같은 주문 id 의 결제가 이미 있다 — 409 {@code DUPLICATE_ORDER} ({@code docs/plan/S6.md} 4.4).
 *
 * <p>판정은 {@code uk_payment_merchant_order} 위반(1062)으로만 한다. 내용이 같아도 409 다. "같은 내용 재전송은 기존 결제로 응답" 은
 * Phase 1 종료 점검의 응답 규약이다.
 */
public class DuplicateOrderException extends DomainException {

  public DuplicateOrderException(String orderId, Throwable cause) {
    super("이미 같은 주문 id 의 결제가 있습니다. orderId=" + orderId);
    initCause(cause);
  }
}
