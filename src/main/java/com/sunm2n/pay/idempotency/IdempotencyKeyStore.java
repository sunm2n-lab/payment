package com.sunm2n.pay.idempotency;

import java.util.Optional;

/**
 * 멱등키 저장소. 키의 범위는 {@code (merchantId, operation)} 이다.
 *
 * <p>JDBC 전용이고 JPA 엔티티를 두지 않는다 ({@code docs/plan/S5.md} 2절). 쓰기는 호출 즉시 SQL 로 나가야 한다 — 영속성 컨텍스트에
 * 쌓였다가 flush 때 나가면 "키를 먼저 쓴다" 는 순서가 코드에만 있고 SQL 에는 없다.
 *
 * <p>인터페이스로 두는 이유는 테스트의 동기화 지점이다. {@code ConcurrencyGateConfig} 가 다른 리포지터리처럼 인터페이스 프록시로 감싼다.
 *
 * <p><b>쓰기는 활성 트랜잭션 안에서만 호출할 수 있다.</b> 트랜잭션 밖에서는 키 INSERT 가 자동 커밋되어, 업무 처리가 실패해도 키가 남는다.
 */
public interface IdempotencyKeyStore {

  /** 잠그지 않고 찾는다. 비유일 인덱스(V4)에서는 같은 키의 행이 여럿일 수 있고, 그중 하나를 돌려준다. */
  Optional<IdempotencyRecord> find(Long merchantId, String operation, String idempotencyKey);

  /**
   * <b>1·2차 실패 배선 전용.</b> 처리가 끝난 뒤 키를 {@code COMPLETED} 로 남긴다. 응답은 저장하지 않는다.
   *
   * <p>"조회 → 처리 → 키 저장" 의 마지막 단계다. 본선은 이 순서를 뒤집어 키를 먼저 쓴다.
   */
  void record(Long merchantId, String operation, String idempotencyKey, String requestHash);
}
