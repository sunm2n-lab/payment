package com.sunm2n.pay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 취소 이력. 취소 합계는 승인 금액 이하여야 하고 {@code payment.balance_amount = amount - 취소 합계} 여야 한다. */
@Entity
@Table(name = "payment_cancel")
public class PaymentCancel {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "payment_id", nullable = false)
  private Long paymentId;

  @Column(name = "cancel_amount", nullable = false)
  private long cancelAmount;

  @Column(name = "reason")
  private String reason;

  @Column(name = "canceled_at", nullable = false)
  private LocalDateTime canceledAt;

  protected PaymentCancel() {}

  public PaymentCancel(Long paymentId, long cancelAmount, String reason) {
    this.paymentId = paymentId;
    this.cancelAmount = cancelAmount;
    this.reason = reason;
    this.canceledAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public Long getPaymentId() {
    return paymentId;
  }

  public long getCancelAmount() {
    return cancelAmount;
  }

  public String getReason() {
    return reason;
  }

  public LocalDateTime getCanceledAt() {
    return canceledAt;
  }
}
