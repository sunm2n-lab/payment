package com.sunm2n.pay.payment.api;

import com.sunm2n.pay.merchant.api.auth.MerchantContext;
import com.sunm2n.pay.payment.api.dto.CancelPaymentRequest;
import com.sunm2n.pay.payment.api.dto.ConfirmPaymentRequest;
import com.sunm2n.pay.payment.api.dto.CreatePaymentRequest;
import com.sunm2n.pay.payment.api.dto.PaymentResponse;
import com.sunm2n.pay.payment.application.PaymentService;
import jakarta.validation.Valid;
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
  private final MerchantContext merchantContext;

  public PaymentController(PaymentService paymentService, MerchantContext merchantContext) {
    this.paymentService = paymentService;
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

  @PostMapping("/{paymentKey}/cancel")
  public PaymentResponse cancel(
      @PathVariable String paymentKey, @Valid @RequestBody CancelPaymentRequest request) {
    return PaymentResponse.from(
        paymentService.cancel(
            merchantContext.merchantId(), paymentKey, request.cancelAmount(), request.reason()));
  }

  @GetMapping("/{paymentKey}")
  public PaymentResponse get(@PathVariable String paymentKey) {
    return PaymentResponse.from(paymentService.get(merchantContext.merchantId(), paymentKey));
  }
}
