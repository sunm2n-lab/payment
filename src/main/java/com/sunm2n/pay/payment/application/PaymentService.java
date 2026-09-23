package com.sunm2n.pay.payment.application;

import com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentCancel;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.PaymentStatus;
import com.sunm2n.pay.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.payment.infrastructure.PaymentCancelRepository;
import com.sunm2n.pay.payment.infrastructure.PaymentRepository;
import com.sunm2n.pay.payment.infrastructure.card.CardApprovalClient;
import com.sunm2n.pay.wallet.domain.Amounts;
import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.domain.WalletLedger;
import com.sunm2n.pay.wallet.domain.exception.WalletNotFoundException;
import com.sunm2n.pay.wallet.infrastructure.WalletLedgerRepository;
import com.sunm2n.pay.wallet.infrastructure.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 서비스. SCENARIO 의 의사코드를 그대로 옮긴 것이다.
 *
 * <p><b>절대 규칙</b>: "읽고 -> 검사하고 -> 계산한 값을 저장한다" (SCENARIO 24행). 잔액 차감도 취소도 조건부 UPDATE 나 락 없이 읽은 값으로
 * 계산해 저장한다. 그래야 S2(차감 유실) / S3(초과 환불)가 재현된다. 승인의 상태 전이만 S1 에서 원자화했고, 그 구현은 {@link PaymentConfirmer}
 * 로 갈라져 있다.
 *
 * <p>가맹점은 요청 파라미터로 받는다. 요청 스코프 {@code MerchantContext} 를 여기서 직접 읽지 않는 이유는 application 계층을 웹 관심사에서
 * 분리해 두기 위해서다 (Phase 2 의 헥사고널 리팩터링 대비). 인증은 api 계층의 인터셉터가 처리하고, 소유권 검사만 이 계층이 한다.
 */
@Service
public class PaymentService {

  private final PaymentRepository paymentRepository;
  private final PaymentCancelRepository paymentCancelRepository;
  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;
  private final CardApprovalClient cardApprovalClient;
  private final PaymentSupport support;
  private final PaymentConfirmer confirmer;

  public PaymentService(
      PaymentRepository paymentRepository,
      PaymentCancelRepository paymentCancelRepository,
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      CardApprovalClient cardApprovalClient,
      PaymentSupport support,
      PaymentConfirmer confirmer) {
    this.paymentRepository = paymentRepository;
    this.paymentCancelRepository = paymentCancelRepository;
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
    this.cardApprovalClient = cardApprovalClient;
    this.support = support;
    this.confirmer = confirmer;
  }

  /** 결제 생성. MONEY 는 memberId 로 지갑을 찾아 wallet_id 를 저장한다. */
  @Transactional
  public Payment create(
      Long merchantId, String orderId, long amount, PaymentMethod method, Long memberId) {
    Long walletId = null;
    if (method == PaymentMethod.MONEY) {
      walletId =
          walletRepository
              .findByMemberId(memberId)
              .orElseThrow(() -> new WalletNotFoundException(memberId))
              .getId();
    }

    Payment payment =
        new Payment(UUID.randomUUID().toString(), orderId, merchantId, walletId, method, amount);
    return paymentRepository.save(payment);
  }

  /**
   * 승인. paymentKey / orderId / amount 세 값이 일치해야 한다.
   *
   * <p>구현은 {@link PaymentConfirmer} 가 소유한다. S1 에서 실패 버전(naive)과 개선 버전(조건부 UPDATE)이 공존하게 되면서 분리했고,
   * 여기서는 기본 구현에 위임만 한다. 트랜잭션 경계도 구현체에 있다.
   */
  public Payment confirm(Long merchantId, String paymentKey, String orderId, long amount) {
    return confirmer.confirm(merchantId, paymentKey, orderId, amount);
  }

  /**
   * 취소/부분취소.
   *
   * <p>잔여 금액 검사와 차감 사이에도 원자성이 없다. 같은 결제에 동시에 들어온 부분취소가 모두 통과해 취소 합계가 승인 금액을 넘는 것이 S3 의 재현 대상이다.
   */
  @Transactional
  public Payment cancel(Long merchantId, String paymentKey, long cancelAmount, String reason) {
    Payment payment = support.findOwnedPayment(merchantId, paymentKey);

    if (payment.getStatus() != PaymentStatus.DONE
        && payment.getStatus() != PaymentStatus.PARTIAL_CANCELED) {
      throw new InvalidPaymentStatusException(paymentKey, payment.getStatus(), "취소");
    }
    if (payment.getBalanceAmount() < cancelAmount) {
      throw new CancelAmountExceededException(paymentKey, payment.getBalanceAmount(), cancelAmount);
    }

    paymentCancelRepository.save(new PaymentCancel(payment.getId(), cancelAmount, reason));

    payment.setBalanceAmount(payment.getBalanceAmount() - cancelAmount);
    payment.setStatus(
        payment.getBalanceAmount() == 0 ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED);

    if (payment.getMethod() == PaymentMethod.MONEY) {
      Wallet wallet = support.loadWallet(payment);
      wallet.setBalance(Amounts.add(wallet.getBalance(), cancelAmount));
      walletLedgerRepository.save(
          new WalletLedger(wallet.getId(), LedgerType.REFUND, cancelAmount, payment.getId()));
    } else {
      cardApprovalClient.cancel(payment.getCardApprovalNo(), cancelAmount);
    }

    return payment;
  }

  @Transactional(readOnly = true)
  public Payment get(Long merchantId, String paymentKey) {
    return support.findOwnedPayment(merchantId, paymentKey);
  }
}
