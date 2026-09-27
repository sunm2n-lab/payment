package com.sunm2n.pay.payment.application.cancellation;

import com.sunm2n.pay.payment.domain.Payment;

/**
 * 취소/부분취소 구현.
 *
 * <p>{@link com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer} 와 같은 이유로 둔 이음매다. 결함을
 * 품은 구현과 개선 구현을 <b>코드에 공존</b>시키고, 테스트가 빈을 골라 쓴다 ({@code docs/plan/PHASE0.md} 2절).
 *
 * <p>구현체가 트랜잭션 경계를 소유한다. 조회부터 검사·취소 이력·환불 또는 카드 취소까지 한 트랜잭션이며, 경계 분리는 S8 이다.
 */
public interface PaymentCanceller {

  Payment cancel(Long merchantId, String paymentKey, long cancelAmount, String reason);
}
