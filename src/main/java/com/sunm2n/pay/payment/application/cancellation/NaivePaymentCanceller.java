package com.sunm2n.pay.payment.application.cancellation;

import com.sunm2n.pay.payment.application.PaymentSupport;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.wallet.application.balance.WalletBalanceUpdater;
import org.springframework.transaction.annotation.Transactional;

/**
 * S3 이전의 취소 — 결함을 품은 구현.
 *
 * <p>잔여 금액 검사는 잠금 없는 consistent read 이고 차감은 flush 시점의 {@code UPDATE payment SET balance_amount =
 * <절대값>} 이다. 같은 결제에 동시에 들어온 부분취소가 모두 같은 잔여 금액을 읽고 검사를 통과해, 취소 합계가 결제 금액을 넘는다 — S3 의 재현 대상이다.
 *
 * <p>개선 후에도 남겨 둔다. 재현 테스트가 이 빈을 직접 호출해 과거의 실패를 계속 재현한다. 잔액 변경 전략은 주입받으며 조합은 {@link
 * com.sunm2n.pay.bootstrap.config.ConcurrencyStrategyConfig} 에 있다.
 */
public class NaivePaymentCanceller implements PaymentCanceller {

  private final PaymentSupport support;
  private final WalletBalanceUpdater updater;

  public NaivePaymentCanceller(PaymentSupport support, WalletBalanceUpdater updater) {
    this.support = support;
    this.updater = updater;
  }

  @Override
  @Transactional
  public Payment cancel(Long merchantId, String paymentKey, long cancelAmount, String reason) {
    Payment payment = support.findOwnedPayment(merchantId, paymentKey);
    return support.cancel(payment, cancelAmount, reason, updater);
  }
}
