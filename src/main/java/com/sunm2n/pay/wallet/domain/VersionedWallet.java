package com.sunm2n.pay.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * 같은 {@code wallet} 테이블을 가리키는 두 번째 엔티티. S2-a 낙관적 락 전용이다.
 *
 * <p><b>{@code @Version} 을 {@link Wallet} 에 붙일 수 없다.</b> 붙이는 순간 naive 구현까지 낙관적 락을 갖게 되어 S2 재현이
 * 사라진다. S1 에서 CAS 를 {@code @Primary} 로 올리자 재현 테스트가 깨졌던 것과 같은 문제이고, 그때의 해법(구현을 갈라 공존시킨다)을 엔티티에 적용한
 * 것이다.
 *
 * <p><b>대신 보호 범위에 제한이 생긴다.</b> 다른 전략의 UPDATE 는 {@code version} 을 올리지 않으므로, 낙관적 락은 <b>참가자가 모두
 * optimistic 전략일 때만</b> 유실을 막는다. 실습 규약으로 고정한다 ({@code docs/plan/S2.md} 2.4).
 *
 * <ul>
 *   <li>낙관적 실험은 참가자 전원이 optimistic 전략이다
 *   <li>낙관적 실험 중 충전은 사전 시딩만 한다
 *   <li>한 트랜잭션에서 {@link Wallet} 과 이 엔티티를 함께 변경하지 않는다
 * </ul>
 *
 * <p>식별자 생성 전략을 적지 않는 이유는 이 엔티티로 지갑을 <b>만들지 않기</b> 때문이다. 조회와 잔액 변경만 한다.
 */
@Entity
@Table(name = "wallet")
public class VersionedWallet {

  @Id private Long id;

  @Column(name = "member_id", nullable = false)
  private Long memberId;

  @Column(name = "balance", nullable = false)
  private long balance;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected VersionedWallet() {}

  public Long getId() {
    return id;
  }

  public Long getMemberId() {
    return memberId;
  }

  public long getBalance() {
    return balance;
  }

  public void setBalance(long balance) {
    this.balance = balance;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public long getVersion() {
    return version;
  }
}
