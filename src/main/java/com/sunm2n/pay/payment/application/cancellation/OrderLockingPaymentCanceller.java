package com.sunm2n.pay.payment.application.cancellation;

import com.sunm2n.pay.payment.application.PaymentSupport;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.exception.OrderNotFoundException;
import com.sunm2n.pay.payment.infrastructure.PaymentRepository;
import com.sunm2n.pay.wallet.application.balance.WalletBalanceUpdater;
import org.springframework.transaction.annotation.Transactional;

/**
 * S6 주문 기반 취소 — 인덱스 없는 조건으로 잠그며 읽는다 (SCENARIO S6 "재현", {@code docs/plan/S6.md} 4.3).
 *
 * <pre>
 * 1. findByMerchantIdAndOrderIdForUpdate   WHERE merchant_id = ? AND order_id = ? FOR UPDATE. 없으면 404
 * 2. 상태 → 금액 검사, 취소 이력, 환불 또는 카드 취소   S3 의 취소 본문 그대로
 * </pre>
 *
 * <p><b>구현은 하나다.</b> 인덱스 없음(V5) → {@code merchant_id} 단일 인덱스(V6) → {@code (merchant_id, order_id)}
 * 복합 unique(V7) 세 단계에서 이 SQL 과 이 코드는 같고 스키마만 다르다. 그래서 실패 버전과 개선 버전을 코드로 가르지 않고 전략 인터페이스도 만들지 않는다.
 * 잠금 범위의 차이는 실행 계획 하나로 설명되어야 한다.
 *
 * <p>잠금 읽기가 이 결제의 <b>첫 로드</b>다. 앞에서 엔티티를 읽어 두지 않으므로 "락은 잡았는데 값은 잠그기 전" 함정이 없다. 대신 {@link
 * PessimisticLockPaymentCanceller} 처럼 "존재 확인 → PK 잠금" 으로 나누지 않았으므로 <b>없는 주문</b>을 찾으면 그 자리의 갭을 잠근다.
 * 알고 두는 한계다 — 고치면 실험 변수가 둘이 된다 (4.7).
 *
 * <p>잠금 순서는 본선과 같은 {@code payment -> wallet} 이다. 환불은 본선과 같은 비관적 락 전략을 넘겨받는다.
 */
public class OrderLockingPaymentCanceller {

  private final PaymentSupport support;
  private final PaymentRepository paymentRepository;
  private final WalletBalanceUpdater updater;

  public OrderLockingPaymentCanceller(
      PaymentSupport support, PaymentRepository paymentRepository, WalletBalanceUpdater updater) {
    this.support = support;
    this.paymentRepository = paymentRepository;
    this.updater = updater;
  }

  @Transactional
  public Payment cancel(Long merchantId, String orderId, long cancelAmount, String reason) {
    Payment payment =
        paymentRepository
            .findByMerchantIdAndOrderIdForUpdate(merchantId, orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    return support.cancel(payment, cancelAmount, reason, updater);
  }
}
