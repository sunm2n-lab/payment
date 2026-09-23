package com.sunm2n.pay.domain;

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
 * 결제 1건.
 *
 * <p>FK 를 만들지 않으므로 {@code merchantId}/{@code walletId} 는 연관이 아니라 원시 식별자로 둔다. 상태 전이를 원자적으로 보호하는
 * 장치(조건부 UPDATE, 비관적 락)는 의도적으로 넣지 않는다 — S1/S3 의 실습 대상이다.
 */
@Entity
@Table(name = "payment")
public class Payment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "payment_key", nullable = false)
  private String paymentKey;

  @Column(name = "order_id", nullable = false)
  private String orderId;

  @Column(name = "merchant_id", nullable = false)
  private Long merchantId;

  /** MONEY 결제의 차감 대상 지갑. CARD 는 null. */
  @Column(name = "wallet_id")
  private Long walletId;

  @Enumerated(EnumType.STRING)
  @Column(name = "method", nullable = false)
  private PaymentMethod method;

  @Column(name = "amount", nullable = false)
  private long amount;

  /** 취소 후 남은 금액. signed 이며 음수를 DB 가 막지 않는다. */
  @Column(name = "balance_amount", nullable = false)
  private long balanceAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private PaymentStatus status;

  @Column(name = "approved_at")
  private LocalDateTime approvedAt;

  @Column(name = "card_approval_no")
  private String cardApprovalNo;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected Payment() {}

  public Payment(
      String paymentKey,
      String orderId,
      Long merchantId,
      Long walletId,
      PaymentMethod method,
      long amount) {
    this.paymentKey = paymentKey;
    this.orderId = orderId;
    this.merchantId = merchantId;
    this.walletId = walletId;
    this.method = method;
    this.amount = amount;
    this.balanceAmount = amount;
    this.status = PaymentStatus.READY;
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getPaymentKey() {
    return paymentKey;
  }

  public String getOrderId() {
    return orderId;
  }

  public Long getMerchantId() {
    return merchantId;
  }

  public Long getWalletId() {
    return walletId;
  }

  public PaymentMethod getMethod() {
    return method;
  }

  public long getAmount() {
    return amount;
  }

  public long getBalanceAmount() {
    return balanceAmount;
  }

  public void setBalanceAmount(long balanceAmount) {
    this.balanceAmount = balanceAmount;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public void setStatus(PaymentStatus status) {
    this.status = status;
  }

  public LocalDateTime getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(LocalDateTime approvedAt) {
    this.approvedAt = approvedAt;
  }

  public String getCardApprovalNo() {
    return cardApprovalNo;
  }

  public void setCardApprovalNo(String cardApprovalNo) {
    this.cardApprovalNo = cardApprovalNo;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
