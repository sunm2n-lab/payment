package com.sunm2n.pay.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 머니 지갑.
 *
 * <p>잔액 변경은 반드시 "읽고 -> 검사하고 -> 계산한 값을 저장한다" 패턴을 따른다 (SCENARIO 24행). 즉 {@code
 * setBalance(getBalance() - amount)} 로 JPA 더티체킹에 맡긴다. {@code UPDATE wallet SET balance = balance -
 * ?} 같은 원자적 감산을 Phase 0 에 넣으면 S2 의 Lost Update 실습이 사라진다.
 *
 * <p>{@code balance} 는 signed 이며 음수를 DB 가 막지 않는다. 음수 검출은 Invariants 가 담당한다.
 */
@Entity
@Table(name = "wallet")
public class Wallet {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "member_id", nullable = false)
  private Long memberId;

  @Column(name = "balance", nullable = false)
  private long balance;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected Wallet() {}

  public Wallet(Long memberId, long balance) {
    this.memberId = memberId;
    this.balance = balance;
    this.createdAt = LocalDateTime.now();
  }

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
}
