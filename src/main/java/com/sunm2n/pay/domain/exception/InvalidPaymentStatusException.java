package com.sunm2n.pay.domain.exception;

import com.sunm2n.pay.domain.PaymentStatus;

/** 현재 상태에서 허용되지 않는 전이다. */
public class InvalidPaymentStatusException extends DomainException {

  public InvalidPaymentStatusException(String paymentKey, PaymentStatus current, String operation) {
    super("현재 상태에서는 " + operation + " 할 수 없습니다. paymentKey=" + paymentKey + ", status=" + current);
  }

  private InvalidPaymentStatusException(String message) {
    super(message);
  }

  /**
   * 동시 승인 경쟁에서 탈락했다 — 조건부 UPDATE 가 0 건을 갱신했다.
   *
   * <p>여기서 현재 상태를 함께 알리지 않는 이유가 있다. 탈락한 요청은 아무 행도 바꾸지 않았고 REPEATABLE READ 의 스냅샷은 첫 조회 시점에 고정돼 있으므로,
   * 평범한 재조회는 승자가 커밋한 최신 상태가 아니라 여전히 {@code READY} 를 돌려준다. 그 값으로 메시지를 만들면 "READY 라서 승인할 수 없다"는 거짓말이
   * 된다. 탈락 판정의 근거는 재조회 값이 아니라 <b>갱신 건수 0</b> 하나뿐이다.
   */
  public static InvalidPaymentStatusException lostConfirmRace(String paymentKey) {
    return new InvalidPaymentStatusException("다른 요청이 먼저 승인을 시작했습니다. paymentKey=" + paymentKey);
  }
}
