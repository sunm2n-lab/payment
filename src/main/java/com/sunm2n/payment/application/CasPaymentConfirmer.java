package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.payment.infrastructure.PaymentRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S1 의 개선 — 조건부 UPDATE 로 상태 전이를 원자화한 승인.
 *
 * <pre>UPDATE payment SET status='IN_PROGRESS' WHERE payment_key=? AND status='READY'</pre>
 *
 * <p>검사와 상태 전이가 한 문장이므로 그 사이에 끼어들 틈이 없다. UPDATE 는 payment 의 X 락을 잡고 트랜잭션 종료까지 유지하며, 첫 요청이 커밋하면 대기하던
 * 요청은 조건을 <b>재평가</b>해 0 건 갱신으로 탈락한다.
 *
 * <p>후보 비교와 선택 이유는 {@code docs/phase1/S1.md} 에 있다.
 */
@Service
@Primary
public class CasPaymentConfirmer implements PaymentConfirmer {

  private final PaymentSupport support;
  private final PaymentRepository paymentRepository;

  public CasPaymentConfirmer(PaymentSupport support, PaymentRepository paymentRepository) {
    this.support = support;
    this.paymentRepository = paymentRepository;
  }

  @Override
  @Transactional
  public Payment confirm(Long merchantId, String paymentKey, String orderId, long amount) {
    Payment payment = support.findOwnedPayment(merchantId, paymentKey);
    support.validateApprovable(payment);
    support.validateRequest(payment, orderId, amount);

    if (paymentRepository.markInProgress(paymentKey) == 0) {
      throw InvalidPaymentStatusException.lostConfirmRace(paymentKey);
    }

    // CAS 가 영속성 컨텍스트를 비웠으므로 위 엔티티는 detached 다. 자기 트랜잭션의 변경은 자기에게 보이므로
    // 재조회하면 IN_PROGRESS 상태의 결제를 얻는다.
    Payment inProgress = support.findOwnedPayment(merchantId, paymentKey);
    return support.approve(inProgress, amount);
  }
}
