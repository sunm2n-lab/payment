package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

  /** 잠금 없는 일반 조회. Phase 0 은 비관적/낙관적 락을 의도적으로 넣지 않는다 — S2 의 Lost Update 실습 대상이다. */
  Optional<Wallet> findByMemberId(Long memberId);

  /**
   * 잔액만 읽는 스칼라 조회. S2 비교 실험 1·2 의 "검사" 읽기다.
   *
   * <p>원자적 UPDATE 를 쓰는 전략은 {@link Wallet} 엔티티를 <b>로드하지 않는다.</b> 엔티티를 들고 있으면 영속성 컨텍스트와 DB 가 갈라지고, 그걸
   * 맞추려고 {@code clearAutomatically} 를 켜는 순간 같은 트랜잭션에서 관리 중이던 <b>결제</b>까지 detached 되어 DONE 전이가 유실된다
   * ({@code docs/plan/S2.md} 2.3). 읽지 않으면 맞출 것도 없다.
   */
  @Query(value = "SELECT balance FROM wallet WHERE id = :id", nativeQuery = true)
  Optional<Long> findBalanceById(@Param("id") Long id);

  /**
   * S2 비교 실험 1 — 원자적 감산. 읽은 값이 아니라 <b>DB 의 현재 값</b>에서 뺀다.
   *
   * <p>차감 유실은 이것으로 사라지지만 잔액 검사는 여전히 이 문장 <b>밖</b>에 있다. 검사를 통과한 요청이 모두 감산에 성공해 잔액이 음수가 되는 것이 이 실험의
   * 관찰 대상이다.
   *
   * <p>{@code @Modifying} 의 두 옵션은 모두 기본값(false)으로 둔다. {@code clearAutomatically} 는 관리 중인 결제를 살려 두기
   * 위해서고, {@code flushAutomatically} 는 이 UPDATE 앞에 먼저 반영해야 할 미반영 변경이 이 흐름에 없기 때문이다. 후자는 Spring Data
   * 가 {@code em.flush()} 를 먼저 부르지 않는다는 뜻일 뿐, Hibernate 의 자동 flush 를 금지하지는 않는다.
   */
  @Modifying
  @Query(value = "UPDATE wallet SET balance = balance - :amount WHERE id = :id", nativeQuery = true)
  int decreaseBalance(@Param("id") Long id, @Param("amount") long amount);

  /**
   * S2 비교 실험 2 — 검사와 감산을 한 문장으로 원자화한다 (SCENARIO 140행). 갱신 건수가 1 일 때만 성공이다.
   *
   * <p>S1 의 {@code markInProgress} 와 같은 모양이다. 검사를 WHERE 로 옮겨 그 사이에 끼어들 틈을 없앤다. 뒤늦게 도착한 요청은 앞선 요청이
   * 커밋한 <b>최신 값</b>으로 조건을 재평가하므로, 잔액이 모자라면 0 건으로 탈락한다.
   *
   * <p>갱신 건수는 matched 든 changed 든 같다. 금액이 양수라 조건에 걸린 행은 반드시 값이 바뀌므로 드라이버의 {@code useAffectedRows}
   * 설정에 결과가 좌우되지 않는다.
   */
  @Modifying
  @Query(
      value = "UPDATE wallet SET balance = balance - :amount WHERE id = :id AND balance >= :amount",
      nativeQuery = true)
  int decreaseBalanceIfEnough(@Param("id") Long id, @Param("amount") long amount);
}
