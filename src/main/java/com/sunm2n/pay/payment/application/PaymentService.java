package com.sunm2n.pay.payment.application;

import com.sunm2n.pay.payment.application.cancellation.OrderLockingPaymentCanceller;
import com.sunm2n.pay.payment.application.cancellation.PaymentCanceller;
import com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.exception.OrderNotFoundException;
import com.sunm2n.pay.payment.infrastructure.PaymentRepository;
import com.sunm2n.pay.wallet.application.WalletService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 서비스. SCENARIO 의 의사코드를 그대로 옮긴 것이다.
 *
 * <p><b>절대 규칙</b>: "읽고 -> 검사하고 -> 계산한 값을 저장한다" (SCENARIO 24행). Phase 0 은 잔액 차감도 취소도 조건부 UPDATE 나 락
 * 없이 읽은 값으로 계산해 저장했고, 그래서 S1(중복 승인) / S2(차감 유실) / S3(초과 환불)가 재현된다. 개선은 이 클래스가 아니라 이음매 뒤의 구현이 한다 —
 * 승인은 {@link PaymentConfirmer}, 취소는 {@link PaymentCanceller}. 실패 버전은 지우지 않고 공존한다.
 *
 * <p>가맹점은 요청 파라미터로 받는다. 요청 스코프 {@code MerchantContext} 를 여기서 직접 읽지 않는 이유는 application 계층을 웹 관심사에서
 * 분리해 두기 위해서다 (Phase 2 의 헥사고널 리팩터링 대비). 인증은 api 계층의 인터셉터가 처리하고, 소유권 검사만 이 계층이 한다.
 */
@Service
public class PaymentService {

  private final PaymentRepository paymentRepository;
  private final WalletService walletService;
  private final PaymentSupport support;
  private final PaymentConfirmer confirmer;
  private final PaymentCanceller canceller;
  private final OrderLockingPaymentCanceller orderCanceller;

  public PaymentService(
      PaymentRepository paymentRepository,
      WalletService walletService,
      PaymentSupport support,
      PaymentConfirmer confirmer,
      PaymentCanceller canceller,
      OrderLockingPaymentCanceller orderCanceller) {
    this.paymentRepository = paymentRepository;
    this.walletService = walletService;
    this.support = support;
    this.confirmer = confirmer;
    this.canceller = canceller;
    this.orderCanceller = orderCanceller;
  }

  /** 결제 생성. MONEY 는 memberId 로 지갑 id 를 찾아 wallet_id 로 저장한다. 잔액은 읽지 않는다. */
  @Transactional
  public Payment create(
      Long merchantId, String orderId, long amount, PaymentMethod method, Long memberId) {
    Long walletId = null;
    if (method == PaymentMethod.MONEY) {
      walletId = walletService.findIdByMemberId(memberId);
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
   * <p>구현은 {@link PaymentCanceller} 가 소유한다. S3 에서 실패 버전(naive)과 개선 버전이 공존하게 되면서 분리했고, 여기서는 기본 구현에
   * 위임만 한다. 트랜잭션 경계도 구현체에 있다.
   */
  public Payment cancel(Long merchantId, String paymentKey, long cancelAmount, String reason) {
    return canceller.cancel(merchantId, paymentKey, cancelAmount, reason);
  }

  /**
   * 주문 id 로 취소한다 (S6). {@code merchant_id} 와 {@code order_id} 로 잠그며 읽는다. 구현과 트랜잭션 경계는 {@link
   * OrderLockingPaymentCanceller} 에 있다.
   */
  public Payment cancelByOrder(Long merchantId, String orderId, long cancelAmount, String reason) {
    return orderCanceller.cancel(merchantId, orderId, cancelAmount, reason);
  }

  @Transactional(readOnly = true)
  public Payment get(Long merchantId, String paymentKey) {
    return support.findOwnedPayment(merchantId, paymentKey);
  }

  /**
   * 주문 id 로 잠그지 않고 조회한다 (S6). 가맹점은 SQL 조건이다 — 다른 가맹점의 주문은 결과가 없어 404 이다. 비교 대조군이라 어느 스키마에서도 잠금 대기에
   * 막히지 않는다.
   */
  @Transactional(readOnly = true)
  public Payment getByOrder(Long merchantId, String orderId) {
    return paymentRepository
        .findByMerchantIdAndOrderId(merchantId, orderId)
        .orElseThrow(() -> new OrderNotFoundException(orderId));
  }
}
