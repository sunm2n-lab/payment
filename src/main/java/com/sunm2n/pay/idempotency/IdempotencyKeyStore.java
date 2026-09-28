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

  /**
   * <b>본선.</b> 키를 먼저 쓴다 (insert-first). 상태는 {@code IN_PROGRESS} 이고, 같은 트랜잭션의 {@link #complete} 가
   * 마무리한다.
   *
   * <p>같은 키의 선행 트랜잭션이 진행 중이면 unique 검사에서 그 트랜잭션이 끝나기를 기다린다. 선행이 커밋하면 {@link
   * IdempotencyKeyClaimedException}, 롤백하면 위반 없이 그대로 성공한다. 대기에는 이 INSERT 한 문장에만 짧은 상한을 두고, 넘으면
   * {@link IdempotencyKeyInUseException} 이다 ({@code docs/plan/S5.md} 4.4).
   *
   * @return 키 행의 id
   */
  long claim(Long merchantId, String operation, String idempotencyKey, String requestHash);

  /** <b>본선.</b> 선점한 키 행에 응답을 저장하고 {@code COMPLETED} 로 바꾼다. 본문은 호출하는 업무가 직렬화한 문자열이다. */
  void complete(long id, int responseStatus, String responseBody);

  /** 잠그지 않고 찾는다. 비유일 인덱스(V4)에서는 같은 키의 행이 여럿일 수 있고, 그중 하나를 돌려준다. */
  Optional<IdempotencyRecord> find(Long merchantId, String operation, String idempotencyKey);

  /**
   * <b>2차 실패 배선 전용.</b> {@code FOR UPDATE} 로 찾는다. 활성 트랜잭션 안에서만 호출할 수 있다.
   *
   * <p>RR 에서 없는 키를 잠그며 찾으면 검색 인덱스의 갭에 X 갭락이 걸린다. 갭락끼리는 공존하므로 두 요청이 모두 "없음" 을 보고 진행하고, 각자의 INSERT 가
   * 상대 갭락에 막혀 데드락이 된다 (SCENARIO S5 "관찰 3", 181행).
   */
  Optional<IdempotencyRecord> findForUpdate(
      Long merchantId, String operation, String idempotencyKey);

  /**
   * <b>1·2차 실패 배선 전용.</b> 처리가 끝난 뒤 키를 {@code COMPLETED} 로 남긴다. 응답은 저장하지 않는다.
   *
   * <p>"조회 → 처리 → 키 저장" 의 마지막 단계다. 본선은 이 순서를 뒤집어 키를 먼저 쓴다.
   */
  void record(Long merchantId, String operation, String idempotencyKey, String requestHash);
}
