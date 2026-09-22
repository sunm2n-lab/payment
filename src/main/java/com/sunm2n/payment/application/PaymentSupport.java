package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.LedgerType;
import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.PaymentMethod;
import com.sunm2n.payment.domain.PaymentStatus;
import com.sunm2n.payment.domain.Wallet;
import com.sunm2n.payment.domain.WalletLedger;
import com.sunm2n.payment.domain.exception.InsufficientBalanceException;
import com.sunm2n.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.payment.domain.exception.PaymentMismatchException;
import com.sunm2n.payment.domain.exception.PaymentNotFoundException;
import com.sunm2n.payment.infrastructure.CardApproval;
import com.sunm2n.payment.infrastructure.CardApprovalClient;
import com.sunm2n.payment.infrastructure.PaymentRepository;
import com.sunm2n.payment.infrastructure.WalletLedgerRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;
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
  private final WalletLedgerRepository walletLedgerRepository;
  private final CardApprovalClient cardApprovalClient;

  public PaymentSupport(
      PaymentRepository paymentRepository,
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      CardApprovalClient cardApprovalClient) {
    this.paymentRepository = paymentRepository;
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
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
   * <p>잔액 차감은 "읽고 -> 검사하고 -> 계산한 값을 저장한다" 그대로다. 원자적 감산으로 바꾸면 S2 의 차감 유실 실습이 사라진다.
   */
  public Payment approve(Payment payment, long amount) {
    if (payment.getMethod() == PaymentMethod.CARD) {
      CardApproval approval = cardApprovalClient.approve(payment.getPaymentKey(), amount);
      payment.setCardApprovalNo(approval.approvalNo());
    } else {
      Wallet wallet = loadWallet(payment);
      if (wallet.getBalance() < amount) {
        throw new InsufficientBalanceException(wallet.getId(), wallet.getBalance(), amount);
      }
      // 차감에는 Amounts 를 쓰지 않는다. 음수 잔액은 S2 의 관찰 대상이다.
      wallet.setBalance(wallet.getBalance() - amount);
      walletLedgerRepository.save(
          new WalletLedger(wallet.getId(), LedgerType.PAY, -amount, payment.getId()));
    }

    payment.setStatus(PaymentStatus.DONE);
    payment.setApprovedAt(LocalDateTime.now());
    return payment;
  }

  /** 생성 시점에 확정된 wallet_id 다. 여기서 없다면 데이터 불일치이므로 예상 밖 오류(5xx)로 둔다. */
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
