package com.sunm2n.pay.idempotency;

/**
 * 같은 키의 선행 요청이 아직 처리 중이고, 선점 INSERT 가 대기 상한을 넘었다 (1205). 409 {@code IDEMPOTENCY_KEY_IN_USE}.
 *
 * <p>재시도 가능한 충돌이다. 선행이 커밋하면 같은 키로 다시 보내 저장된 응답을 받고, 롤백하면 다시 선점한다. 1205 는 문장만 롤백하고 트랜잭션은 열어 두므로, 이
 * 예외는 선점 트랜잭션 밖으로 나가 <b>전체</b>를 롤백시킨다.
 */
public class IdempotencyKeyInUseException extends RuntimeException {

  public IdempotencyKeyInUseException(String idempotencyKey, Throwable cause) {
    super("같은 멱등키의 요청이 처리 중입니다. 잠시 후 다시 시도하세요. key=" + idempotencyKey, cause);
  }
}
