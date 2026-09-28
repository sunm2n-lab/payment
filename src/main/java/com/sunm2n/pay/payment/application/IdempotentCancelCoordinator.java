package com.sunm2n.pay.payment.application;

/**
 * 취소의 멱등성 조율자. 컨트롤러는 이 클래스만 부른다 ({@code docs/plan/S5.md} 4.3).
 *
 * <p>{@link PaymentService#cancel} 은 그대로 두고 여기서 위임한다. 그 메서드는 스스로 트랜잭션을 열지 않고 본선 canceller 에 위임하므로,
 * 조율자가 연 트랜잭션 안에서 부르면 canceller 가 그 트랜잭션에 참여한다.
 *
 * <p>조율자 자신은 트랜잭션이 없다. 키가 없으면 기존 경로 그대로다 — 중복 방지 없이 canceller 의 트랜잭션 하나로 끝난다.
 */
public class IdempotentCancelCoordinator {

  private final PaymentService paymentService;

  public IdempotentCancelCoordinator(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  /** 키 없는 경로. 저장하지 않는다. */
  public CancelOutcome cancel(
      Long merchantId, String paymentKey, long cancelAmount, String reason) {
    return CancelOutcome.ok(
        PaymentSnapshot.from(paymentService.cancel(merchantId, paymentKey, cancelAmount, reason)));
  }
}
