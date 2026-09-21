package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.LedgerType;
import com.sunm2n.payment.domain.Payment;
import com.sunm2n.payment.domain.PaymentCancel;
import com.sunm2n.payment.domain.PaymentMethod;
import com.sunm2n.payment.domain.PaymentStatus;
import com.sunm2n.payment.domain.Wallet;
import com.sunm2n.payment.domain.WalletLedger;
import com.sunm2n.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.payment.domain.exception.InsufficientBalanceException;
import com.sunm2n.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.payment.domain.exception.PaymentMismatchException;
import com.sunm2n.payment.domain.exception.PaymentNotFoundException;
import com.sunm2n.payment.domain.exception.WalletNotFoundException;
import com.sunm2n.payment.infrastructure.CardApproval;
import com.sunm2n.payment.infrastructure.CardApprovalClient;
import com.sunm2n.payment.infrastructure.PaymentCancelRepository;
import com.sunm2n.payment.infrastructure.PaymentRepository;
import com.sunm2n.payment.infrastructure.WalletLedgerRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * naive 결제 서비스. SCENARIO 의 의사코드를 그대로 옮긴 것이다.
 *
 * <p><b>절대 규칙</b>: "읽고 -> 검사하고 -> 계산한 값을 저장한다" (SCENARIO 24행). 상태 전이도 잔액 차감도 조건부 UPDATE 나 락 없이 읽은
 * 값으로 계산해 저장한다. 그래야 S1(중복 승인) / S2(차감 유실) / S3(초과 환불)가 재현된다.
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

  public PaymentService(
      PaymentRepository paymentRepository,
      PaymentCancelRepository paymentCancelRepository,
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      CardApprovalClient cardApprovalClient) {
    this.paymentRepository = paymentRepository;
    this.paymentCancelRepository = paymentCancelRepository;
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
    this.cardApprovalClient = cardApprovalClient;
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
   * <p>READY 검사와 DONE 갱신 사이에 원자성이 없다. 동시에 들어온 confirm 이 모두 READY 를 읽고 승인에 성공하는 것이 S1 의 재현 대상이다.
   * CARD 승인 호출은 SCENARIO 대로 트랜잭션 안에서 한다 (외부 호출 동안 커넥션을 붙들고 있는 문제는 S8 에서 다룬다).
   */
  @Transactional
  public Payment confirm(Long merchantId, String paymentKey, String orderId, long amount) {
    Payment payment = findOwnedPayment(merchantId, paymentKey);

    if (payment.getStatus() != PaymentStatus.READY) {
      throw new InvalidPaymentStatusException(paymentKey, payment.getStatus(), "승인");
    }
    if (!payment.getOrderId().equals(orderId)) {
      throw new PaymentMismatchException(paymentKey, "orderId");
    }
    if (payment.getAmount() != amount) {
      throw new PaymentMismatchException(paymentKey, "amount");
    }

    if (payment.getMethod() == PaymentMethod.CARD) {
      CardApproval approval = cardApprovalClient.approve(paymentKey, amount);
      payment.setCardApprovalNo(approval.approvalNo());
    } else {
      Wallet wallet = loadWallet(payment);
      if (wallet.getBalance() < amount) {
        throw new InsufficientBalanceException(wallet.getId(), wallet.getBalance(), amount);
      }
      wallet.setBalance(wallet.getBalance() - amount);
      walletLedgerRepository.save(
          new WalletLedger(wallet.getId(), LedgerType.PAY, -amount, payment.getId()));
    }

    payment.setStatus(PaymentStatus.DONE);
    payment.setApprovedAt(LocalDateTime.now());
    return payment;
  }

  /**
   * 취소/부분취소.
   *
   * <p>잔여 금액 검사와 차감 사이에도 원자성이 없다. 같은 결제에 동시에 들어온 부분취소가 모두 통과해 취소 합계가 승인 금액을 넘는 것이 S3 의 재현 대상이다.
   */
  @Transactional
  public Payment cancel(Long merchantId, String paymentKey, long cancelAmount, String reason) {
    Payment payment = findOwnedPayment(merchantId, paymentKey);

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
      Wallet wallet = loadWallet(payment);
      wallet.setBalance(wallet.getBalance() + cancelAmount);
      walletLedgerRepository.save(
          new WalletLedger(wallet.getId(), LedgerType.REFUND, cancelAmount, payment.getId()));
    } else {
      cardApprovalClient.cancel(payment.getCardApprovalNo(), cancelAmount);
    }

    return payment;
  }

  @Transactional(readOnly = true)
  public Payment get(Long merchantId, String paymentKey) {
    return findOwnedPayment(merchantId, paymentKey);
  }

  /**
   * payment_key unique 인덱스로 찾은 뒤 가맹점 일치를 메모리에서 검사한다.
   *
   * <p>다른 가맹점의 결제는 "없음"으로 응답해 존재를 노출하지 않는다. 검사를 SQL 조건이 아니라 메모리에서 하므로 S6 의 {@code merchant_id} 인덱스
   * 부재 실험에는 영향이 없다.
   */
  private Payment findOwnedPayment(Long merchantId, String paymentKey) {
    Payment payment =
        paymentRepository
            .findByPaymentKey(paymentKey)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));

    if (!payment.getMerchantId().equals(merchantId)) {
      throw new PaymentNotFoundException(paymentKey);
    }
    return payment;
  }

  /** 생성 시점에 확정된 wallet_id 다. 여기서 없다면 데이터 불일치이므로 예상 밖 오류(5xx)로 둔다. */
  private Wallet loadWallet(Payment payment) {
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
