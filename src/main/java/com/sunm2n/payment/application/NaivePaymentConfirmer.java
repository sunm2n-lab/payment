package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S1 이전의 승인 — 결함을 품은 구현.
 *
 * <p>READY 검사는 잠금 없는 consistent read 이고 DONE 갱신은 flush 시점의 UPDATE 다. 둘 사이에 원자성이 없으므로 같은 {@code
 * paymentKey} 로 동시에 들어온 승인이 모두 READY 를 읽고 모두 성공한다 — S1 의 재현 대상이다.
 *
 * <p>개선 후에도 남겨 둔다. {@code DuplicateConfirmReproductionTest} 가 이 빈을 직접 호출해 과거의 실패를 계속 재현한다.
 */
@Service
public class NaivePaymentConfirmer implements PaymentConfirmer {

  private final PaymentSupport support;

  public NaivePaymentConfirmer(PaymentSupport support) {
    this.support = support;
  }

  @Override
  @Transactional
  public Payment confirm(Long merchantId, String paymentKey, String orderId, long amount) {
    Payment payment = support.findOwnedPayment(merchantId, paymentKey);
    support.validateApprovable(payment);
    support.validateRequest(payment, orderId, amount);
    return support.approve(payment, amount);
  }
}
