package com.sunm2n.pay.idempotency;

/**
 * 키 행의 처리 상태.
 *
 * <p>S5 는 키 INSERT·업무 처리·결과 저장이 한 트랜잭션이라 커밋된 값은 항상 {@link #COMPLETED} 다. {@link #IN_PROGRESS} 는 같은
 * 트랜잭션 안에서만 보인다. 처리 중 상태가 따로 커밋되는 것은 S8 부터다.
 */
public enum IdempotencyStatus {
  IN_PROGRESS,
  COMPLETED
}
