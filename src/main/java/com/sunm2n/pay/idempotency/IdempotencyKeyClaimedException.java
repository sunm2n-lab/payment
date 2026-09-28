package com.sunm2n.pay.idempotency;

/**
 * 키 선점 충돌 — 같은 키가 이미 커밋되어 있다. 선점 INSERT 의 {@code uk_idempotency_key} 위반(1062)으로만 판정한다.
 *
 * <p>응답으로 번역되지 않는다. 조율자가 선점 트랜잭션 밖에서 잡아, 새 트랜잭션에서 저장된 응답을 읽는다 ({@code docs/plan/S5.md} 4.3). 취소 과정의
 * 다른 무결성 예외는 이 타입이 되지 않는다.
 */
public class IdempotencyKeyClaimedException extends RuntimeException {

  public IdempotencyKeyClaimedException(String idempotencyKey, Throwable cause) {
    super("이미 선점된 멱등키입니다. key=" + idempotencyKey, cause);
  }
}
