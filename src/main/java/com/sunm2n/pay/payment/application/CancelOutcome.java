package com.sunm2n.pay.payment.application;

/**
 * 취소 결과 — HTTP 상태와 본문.
 *
 * <p>상태는 정수다. application 계층이 {@code HttpStatus} 에 의존하지 않는다. S5 에서 저장·재생하는 것은 성공(200)뿐이지만, 저장된 응답을
 * 그대로 돌려주려면 상태도 결과의 일부여야 한다.
 */
public record CancelOutcome(int status, PaymentSnapshot body) {

  static final int OK = 200;

  static CancelOutcome ok(PaymentSnapshot body) {
    return new CancelOutcome(OK, body);
  }
}
