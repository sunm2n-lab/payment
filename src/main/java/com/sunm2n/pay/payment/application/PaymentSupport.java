package com.sunm2n.pay.payment.application;

import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.PaymentStatus;
import com.sunm2n.pay.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.payment.domain.exception.PaymentMismatchException;
import com.sunm2n.pay.payment.domain.exception.PaymentNotFoundException;
import com.sunm2n.pay.payment.infrastructure.PaymentRepository;
import com.sunm2n.pay.payment.infrastructure.card.CardApproval;
import com.sunm2n.pay.payment.infrastructure.card.CardApprovalClient;
import com.sunm2n.pay.wallet.application.balance.WalletBalanceUpdater;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.infrastructure.WalletRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 결제 유스케이스가 공유하는 조회·검증·승인 본문.
 *
 * <p>S1 에서 confirm 구현이 naive 와 CAS 둘로 갈라지면서 뽑아낸 것이다. 두 구현의 차이는 <b>상태 전이를 어떻게 확정하느냐</b> 하나뿐이고 나머지는
 * 같아야 한다. 그래야 재현과 개선의 비교가 그 차이 하나로 설명된다.
 *
 * <p>스스로 트랜잭션을 열지 않는다. 호출자(confirmer, {@link PaymentService})의 트랜잭션에 참여한다.
 */
@Component
public class PaymentSupport {

  private final PaymentRepository paymentRepository;
  private final WalletRepository walletRepository;
  private final CardApprovalClient cardApprovalClient;

  public PaymentSupport(
      PaymentRepository paymentRepository,
      WalletRepository walletRepository,
      CardApprovalClient cardApprovalClient) {
    this.paymentRepository = paymentRepository;
    this.walletRepository = walletRepository;
    this.cardApprovalClient = cardApprovalClient;
  }

  /**
   * {@code payment_key} unique 인덱스로 찾은 뒤 가맹점 일치를 메모리에서 검사한다.
   *
   * <p>다른 가맹점의 결제는 "없음"으로 응답해 존재를 노출하지 않는다. 검사를 SQL 조건이 아니라 메모리에서 하므로 S6 의 {@code merchant_id} 인덱스
   * 부재 실험에는 영향이 없다.
   */
  public Payment findOwnedPayment(Long merchantId, String paymentKey) {
    Payment payment =
        paymentRepository
            .findByPaymentKey(paymentKey)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));

    if (!payment.getMerchantId().equals(merchantId)) {
      throw new PaymentNotFoundException(paymentKey);
    }
    return payment;
  }

  /**
   * 승인 가능한 상태인지 사전 검사한다.
   *
   * <p>CAS 구현에서도 이 검사를 남긴다. 조건부 UPDATE 만으로도 경쟁은 막히지만, 검사를 없애면 <b>이미 DONE 인데 요청 값도 틀린</b> 경우의 응답이
   * {@code INVALID_PAYMENT_STATUS} 에서 {@code PAYMENT_MISMATCH} 로 바뀐다. 기존 오류 우선순위를 보존하려고 남겨 둔 것이고,
   * 경쟁의 최종 판정은 어디까지나 CAS 가 한다.
   */
  public void validateApprovable(Payment payment) {
    if (payment.getStatus() != PaymentStatus.READY) {
      throw new InvalidPaymentStatusException(payment.getPaymentKey(), payment.getStatus(), "승인");
    }
  }

  /** 승인 요청의 orderId / amount 가 저장된 결제와 일치하는지 검사한다. */
  public void validateRequest(Payment payment, String orderId, long amount) {
    if (!payment.getOrderId().equals(orderId)) {
      throw new PaymentMismatchException(payment.getPaymentKey(), "orderId");
    }
    if (payment.getAmount() != amount) {
      throw new PaymentMismatchException(payment.getPaymentKey(), "amount");
    }
  }

  /**
   * 승인 본문. 카드사 승인 또는 지갑 차감을 수행하고 DONE 으로 바꾼다.
   *
   * <p>CARD 승인 호출은 SCENARIO 대로 호출자의 트랜잭션 안에서 한다. 외부 호출 동안 커넥션과 락을 붙들고 있는 문제는 S8 에서 다룬다.
   *
   * <p>차감 방식은 {@link WalletBalanceUpdater} 가 소유한다. S2 에서 전략이 다섯으로 갈라지면서 분리했고, 전략은 <b>호출자가 넘긴다.</b>
   *
   * <p>차감을 전략에 맡겨도 DONE 전이는 여기 <b>마지막에</b> 남는다. 그래서 전략이 영속성 컨텍스트를 비우면(예: {@code @Modifying(
   * clearAutomatically = true)}) 관리 중이던 이 엔티티가 detached 되어 상태 전이와 {@code approved_at} 이 통째로 유실된다 —
   * 결제가 {@code IN_PROGRESS} 로 커밋되는 사고다. 그래서 원자적 UPDATE 를 쓰는 전략은 {@code Wallet} 엔티티를 아예 로드하지 않는다
   * ({@code docs/plan/S2.md} 2.3).
   */
  public Payment approve(Payment payment, long amount, WalletBalanceUpdater updater) {
    if (payment.getMethod() == PaymentMethod.CARD) {
      CardApproval approval = cardApprovalClient.approve(payment.getPaymentKey(), amount);
      payment.setCardApprovalNo(approval.approvalNo());
    } else {
      updater.debit(payment.getWalletId(), payment.getId(), amount);
    }

    payment.setStatus(PaymentStatus.DONE);
    payment.setApprovedAt(LocalDateTime.now());
    return payment;
  }

  /**
   * 취소의 REFUND 경로가 쓰는 지갑 조회. 생성 시점에 확정된 wallet_id 이며, 여기서 없다면 데이터 불일치이므로 예상 밖 오류(5xx)로 둔다.
   *
   * <p>승인 경로는 더 이상 이 메서드를 쓰지 않는다. 지갑을 어떻게 읽느냐가 전략의 일부이기 때문이다 (잠금 없이 읽을지, {@code FOR UPDATE} 로 잠그며
   * 읽을지, 아예 엔티티로 읽지 않을지). 취소에 같은 결론을 적용하는 것은 S3 다.
   */
  public Wallet loadWallet(Payment payment) {
    return walletRepository
        .findById(payment.getWalletId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "결제에 연결된 지갑이 없습니다. paymentKey="
                        + payment.getPaymentKey()
                        + ", walletId="
                        + payment.getWalletId()));
  }
}
