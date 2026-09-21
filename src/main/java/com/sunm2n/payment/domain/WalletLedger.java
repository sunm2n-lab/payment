package com.sunm2n.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 지갑 원장(append only).
 *
 * <p>FK 를 만들지 않으므로 {@code walletId}/{@code paymentId} 는 연관이 아니라 원시 식별자로 둔다. S4 에서 {@code
 * wallet_ledger.wallet_id -> wallet.id} FK 를 처음 추가한다.
 */
@Entity
@Table(name = "wallet_ledger")
public class WalletLedger {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "wallet_id", nullable = false)
  private Long walletId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private LedgerType type;

  /** CHARGE/REFUND 는 양수, PAY 는 음수. 지갑 잔액은 이 값들의 합과 같아야 한다. */
  @Column(name = "amount", nullable = false)
  private long amount;

  @Column(name = "payment_id")
  private Long paymentId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected WalletLedger() {}

  public WalletLedger(Long walletId, LedgerType type, long amount, Long paymentId) {
    this.walletId = walletId;
    this.type = type;
    this.amount = amount;
    this.paymentId = paymentId;
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public Long getWalletId() {
    return walletId;
  }

  public LedgerType getType() {
    return type;
  }

  public long getAmount() {
    return amount;
  }

  public Long getPaymentId() {
    return paymentId;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
