package com.sunm2n.pay.payment.application.cancellation;

import com.sunm2n.pay.idempotency.IdempotencyKeyStore;
import com.sunm2n.pay.payment.application.CancelFingerprint;
import com.sunm2n.pay.payment.application.PaymentSupport;
import com.sunm2n.pay.payment.domain.Payment;
import org.springframework.transaction.annotation.Transactional;

/**
 * S5 1차 — check-then-insert. <b>실패 배선</b>이다 (SCENARIO S5 "개선 1차", 178행).
 *
 * <pre>
 * 1. 키 조회 (잠금 없음)     있으면 처리하지 않는다
 * 2. 취소                    본선 canceller 가 이 트랜잭션에 참여
 * 3. 키 저장                 COMPLETED, 응답 없음
 * </pre>
 *
 * <p>두 요청이 1 을 모두 마친 뒤 진행하면 둘 다 "없음" 을 보고 둘 다 취소한다. 취소는 <b>본선</b> canceller 를 그대로 쓴다 — 결제 {@code
 * FOR UPDATE} 로 금액 상한은 지켜지는데 같은 요청이 두 번 실행된다는 것이 1차의 요점이다. V4 의 검색 인덱스는 비유일이라 키 행도 두 건 남는다.
 *
 * <p>응답을 저장하지도 재생하지도 않는다. 키가 이미 있으면 현재 결제를 조회해 돌려준다 ({@code docs/plan/S5.md} 2절). 1·2차의 관심은 중복 실행과
 * 데드락이다.
 */
public class CheckThenInsertIdempotentCanceller {

  private final IdempotencyKeyStore store;
  private final PaymentCanceller canceller;
  private final PaymentSupport support;

  public CheckThenInsertIdempotentCanceller(
      IdempotencyKeyStore store, PaymentCanceller canceller, PaymentSupport support) {
    this.store = store;
    this.canceller = canceller;
    this.support = support;
  }

  @Transactional
  public Payment cancel(
      Long merchantId, String idempotencyKey, String paymentKey, long cancelAmount, String reason) {
    if (store.find(merchantId, CancelFingerprint.OPERATION, idempotencyKey).isPresent()) {
      return support.findOwnedPayment(merchantId, paymentKey);
    }
    Payment payment = canceller.cancel(merchantId, paymentKey, cancelAmount, reason);
    store.record(
        merchantId,
        CancelFingerprint.OPERATION,
        idempotencyKey,
        CancelFingerprint.hash(paymentKey, cancelAmount, reason));
    return payment;
  }
}
