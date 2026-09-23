package com.sunm2n.pay.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 가맹점별 일 정산.
 *
 * <p>SCENARIO 1절대로 V1 에 만들지만 Phase 0 에서 이 엔티티를 쓰는 코드는 없다. {@code ddl-auto: validate} 외에는 검증되지 않은 채
 * S10 까지 남는다 — 의도한 것이다.
 *
 * <p>{@code totalAmount} 는 수수료 차감 전 금액(확정 승인 합계 - 확정 취소 합계)이고, 지급액은 컬럼으로 두지 않고 {@code totalAmount -
 * fee} 로 계산한다. {@code status} 의 값 집합은 S10 에서 정하므로 아직 enum 으로 굳히지 않는다. {@code (merchant_id,
 * settlement_date)} unique 도 S10 의 대상이라 V1 에는 없다.
 */
@Entity
@Table(name = "settlement")
public class Settlement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "merchant_id", nullable = false)
  private Long merchantId;

  @Column(name = "settlement_date", nullable = false)
  private LocalDate settlementDate;

  @Column(name = "total_amount", nullable = false)
  private long totalAmount;

  @Column(name = "fee", nullable = false)
  private long fee;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected Settlement() {}

  public Settlement(
      Long merchantId, LocalDate settlementDate, long totalAmount, long fee, String status) {
    this.merchantId = merchantId;
    this.settlementDate = settlementDate;
    this.totalAmount = totalAmount;
    this.fee = fee;
    this.status = status;
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public Long getMerchantId() {
    return merchantId;
  }

  public LocalDate getSettlementDate() {
    return settlementDate;
  }

  public long getTotalAmount() {
    return totalAmount;
  }

  public long getFee() {
    return fee;
  }

  public String getStatus() {
    return status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
