package com.sunm2n.pay.application;

import com.sunm2n.pay.domain.Payment;
import com.sunm2n.pay.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.infrastructure.PaymentRepository;
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
 *
 * <p>이 구현이 막는 것은 <b>같은 결제</b>의 경쟁뿐이다. 조건부 UPDATE 가 잠그는 것은 {@code payment} 행 하나이므로, 서로 다른 결제가 같은 지갑을
 * 건드리는 S2 의 경쟁은 잔액 변경 전략이 맡는다. 그 전략을 주입받으며, 조합은 {@link ConcurrencyStrategyConfig} 에 있다.
 */
public class CasPaymentConfirmer implements PaymentConfirmer {

  private final PaymentSupport support;
  private final PaymentRepository paymentRepository;
  private final WalletBalanceUpdater updater;

  public CasPaymentConfirmer(
      PaymentSupport support, PaymentRepository paymentRepository, WalletBalanceUpdater updater) {
    this.support = support;
    this.paymentRepository = paymentRepository;
    this.updater = updater;
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
    return support.approve(inProgress, amount, updater);
  }
}
