package com.sunm2n.pay.payment.application.cancellation;

import com.sunm2n.pay.payment.application.PaymentSupport;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.infrastructure.PaymentRepository;
import com.sunm2n.pay.wallet.application.balance.WalletBalanceUpdater;
import org.springframework.transaction.annotation.Transactional;

/**
 * S3 비관적 락 취소 — <b>본선</b> (SCENARIO 155행, S2-b 결론 적용).
 *
 * <pre>
 * 1. findOwnedPaymentId       잠그지 않는 스칼라 조회 (id, merchantId). 없거나 남의 것이면 404
 * 2. findByIdForUpdate(id)    PK 레코드 X 락. 엔티티는 여기서 처음 로드한다
 * 3. 상태 → 금액 검사          잠근 뒤의 현재 값으로
 * 4. 취소 이력, 잔여 금액·상태, 환불(wallet FOR UPDATE) 또는 카드 취소
 * </pre>
 *
 * <p><b>바뀌지 않는 사실(존재, 소유 가맹점)은 잠그기 전에, 바뀌는 사실(상태, 잔여 금액)은 잠근 뒤에</b> 검사한다. naive 와 달리 "읽고 -> 검사하고 ->
 * 계산한 값을 저장한다" 모양을 그대로 두고도 유실이 사라진다. 뒤이어 온 요청은 앞선 요청이 커밋한 값을 보고 검사한다. 잠금 읽기는 스냅샷이 아니라 최신 커밋 값을
 * 읽으므로, 탈락자의 예외 메시지에 담긴 잔여 금액도 사실이다.
 *
 * <p>잠금 순서는 승인과 같은 {@code payment -> wallet} 이다. 같은 결제에서 승인과 겹치면 payment 락을 먼저 잡은 쪽이 결과를 정한다 — 승인이
 * 먼저 잡고 커밋하면 취소는 대기 후 DONE 을, 취소가 먼저 잡거나 승인이 롤백하면 READY 를 읽고 상태 오류로 끝난다.
 */
public class PessimisticLockPaymentCanceller implements PaymentCanceller {

  private final PaymentSupport support;
  private final PaymentRepository paymentRepository;
  private final WalletBalanceUpdater updater;

  public PessimisticLockPaymentCanceller(
      PaymentSupport support, PaymentRepository paymentRepository, WalletBalanceUpdater updater) {
    this.support = support;
    this.paymentRepository = paymentRepository;
    this.updater = updater;
  }

  @Override
  @Transactional
  public Payment cancel(Long merchantId, String paymentKey, long cancelAmount, String reason) {
    Long paymentId = support.findOwnedPaymentId(merchantId, paymentKey);
    Payment payment =
        paymentRepository
            .findByIdForUpdate(paymentId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "확인한 결제가 잠그는 사이 사라졌습니다. 물리 삭제 경로가 없으므로 데이터 불일치다. paymentKey="
                            + paymentKey));
    return support.cancel(payment, cancelAmount, reason, updater);
  }
}
