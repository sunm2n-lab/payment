package com.sunm2n.pay.payment.application.cancellation;

import com.sunm2n.pay.idempotency.IdempotencyKeyStore;
import com.sunm2n.pay.payment.application.CancelFingerprint;
import com.sunm2n.pay.payment.application.PaymentSupport;
import com.sunm2n.pay.payment.domain.Payment;
import org.springframework.transaction.annotation.Transactional;

/**
 * S5 2차 — 키 조회에 {@code FOR UPDATE} 를 더한 check-then-insert. <b>실패 배선</b>이다 (SCENARIO S5 "개선 2차",
 * 180행).
 *
 * <pre>
 * T1  SELECT key FOR UPDATE      없음 → 갭 X
 * T2  SELECT key FOR UPDATE      없음 → 갭 X   (갭락끼리 공존)
 * T1  payment FOR UPDATE         획득
 * T2  payment FOR UPDATE         T1 에 막힘
 * T1  취소 … INSERT key          insert intention → T2 의 갭 X 에 막힘 → 순환 (1213)
 * </pre>
 *
 * <p>{@link CheckThenInsertIdempotentCanceller} 와 조회 한 줄만 다르다. 없는 행은 잠글 수 없으므로 {@code FOR UPDATE} 는
 * 행이 아니라 <b>갭</b>을 잠그고, 갭락은 서로 막지 않는다. 결과적으로 중복은 막히지만 데드락 덕분이다 ({@code docs/plan/S5.md} 4.7).
 */
public class LockingLookupIdempotentCanceller {

  private final IdempotencyKeyStore store;
  private final PaymentCanceller canceller;
  private final PaymentSupport support;

  public LockingLookupIdempotentCanceller(
      IdempotencyKeyStore store, PaymentCanceller canceller, PaymentSupport support) {
    this.store = store;
    this.canceller = canceller;
    this.support = support;
  }

  @Transactional
  public Payment cancel(
      Long merchantId, String idempotencyKey, String paymentKey, long cancelAmount, String reason) {
    if (store.findForUpdate(merchantId, CancelFingerprint.OPERATION, idempotencyKey).isPresent()) {
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
