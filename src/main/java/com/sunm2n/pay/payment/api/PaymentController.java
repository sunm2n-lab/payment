package com.sunm2n.pay.payment.api;

import com.sunm2n.pay.merchant.api.auth.MerchantContext;
import com.sunm2n.pay.payment.api.dto.CancelPaymentRequest;
import com.sunm2n.pay.payment.api.dto.ConfirmPaymentRequest;
import com.sunm2n.pay.payment.api.dto.CreatePaymentRequest;
import com.sunm2n.pay.payment.api.dto.PaymentResponse;
import com.sunm2n.pay.payment.application.CancelOutcome;
import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.PaymentSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

  private final PaymentService paymentService;
  private final IdempotentCancelCoordinator cancelCoordinator;
  private final MerchantContext merchantContext;

  public PaymentController(
      PaymentService paymentService,
      IdempotentCancelCoordinator cancelCoordinator,
      MerchantContext merchantContext) {
    this.paymentService = paymentService;
    this.cancelCoordinator = cancelCoordinator;
    this.merchantContext = merchantContext;
  }

  @PostMapping
  public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request) {
    return PaymentResponse.from(
        paymentService.create(
            merchantContext.merchantId(),
            request.orderId(),
            request.amount(),
            request.method(),
            request.memberId()));
  }

  @PostMapping("/confirm")
  public PaymentResponse confirm(@Valid @RequestBody ConfirmPaymentRequest request) {
    return PaymentResponse.from(
        paymentService.confirm(
            merchantContext.merchantId(),
            request.paymentKey(),
            request.orderId(),
            request.amount()));
  }

  /**
   * 취소는 조율자의 결과를 상태·본문 그대로 쓴다. 멱등키로 재생한 응답은 {@code Payment} 가 아니라 저장된 스냅샷이므로 {@link
   * PaymentResponse#from} 으로 만들 수 없다 ({@code docs/plan/S5.md} 4.5).
   */
  @PostMapping("/{paymentKey}/cancel")
  public ResponseEntity<PaymentSnapshot> cancel(
      @PathVariable String paymentKey, @Valid @RequestBody CancelPaymentRequest request) {
    CancelOutcome outcome =
        cancelCoordinator.cancel(
            merchantContext.merchantId(), paymentKey, request.cancelAmount(), request.reason());
    return ResponseEntity.status(outcome.status()).body(outcome.body());
  }

  @GetMapping("/{paymentKey}")
  public PaymentResponse get(@PathVariable String paymentKey) {
    return PaymentResponse.from(paymentService.get(merchantContext.merchantId(), paymentKey));
  }
}
