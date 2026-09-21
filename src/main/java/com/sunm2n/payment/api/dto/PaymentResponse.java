package com.sunm2n.payment.api.dto;

import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.PaymentMethod;
import com.sunm2n.payment.domain.PaymentStatus;
import java.time.LocalDateTime;

/** 승인 응답도 status 를 포함한다. k6 가 승인 결과를 응답만으로 검증한다. */
public record PaymentResponse(
    String paymentKey,
    String orderId,
    PaymentMethod method,
    long amount,
    long balanceAmount,
    PaymentStatus status,
    LocalDateTime approvedAt,
    String cardApprovalNo) {

  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.getPaymentKey(),
        payment.getOrderId(),
        payment.getMethod(),
        payment.getAmount(),
        payment.getBalanceAmount(),
        payment.getStatus(),
        payment.getApprovedAt(),
        payment.getCardApprovalNo());
  }
}
