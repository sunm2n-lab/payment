package com.sunm2n.pay.payment.application;

import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.PaymentStatus;
import java.time.LocalDateTime;

/**
 * 응답 시점의 결제 스냅샷. {@code PaymentResponse} 와 필드가 같다.
 *
 * <p>멱등키로 재전송된 요청에는 <b>그때의 응답</b>을 돌려줘야 한다. 결제를 다시 조회해 만들면 그 사이의 추가 취소가 섞인다. 그래서 application 계층이 응답
 * 모양을 한 값을 만들어 저장하고, 재생할 때 그대로 되읽는다 ({@code docs/plan/S5.md} 4.5). 최초 응답도 재생 응답도 이 타입을 MVC 가 직렬화한
 * 것이다.
 */
public record PaymentSnapshot(
    String paymentKey,
    String orderId,
    PaymentMethod method,
    long amount,
    long balanceAmount,
    PaymentStatus status,
    LocalDateTime approvedAt,
    String cardApprovalNo) {

  public static PaymentSnapshot from(Payment payment) {
    return new PaymentSnapshot(
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
