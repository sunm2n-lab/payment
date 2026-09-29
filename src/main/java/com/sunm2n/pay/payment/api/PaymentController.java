package com.sunm2n.pay.payment.api;

import com.sunm2n.pay.idempotency.IdempotencyKeys;
import com.sunm2n.pay.merchant.api.auth.MerchantContext;
import com.sunm2n.pay.payment.api.dto.CancelPaymentRequest;
import com.sunm2n.pay.payment.api.dto.ConfirmPaymentRequest;
import com.sunm2n.pay.payment.api.dto.CreatePaymentRequest;
import com.sunm2n.pay.payment.api.dto.PaymentResponse;
import com.sunm2n.pay.payment.application.CancelOutcome;
import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.PaymentSnapshot;
import com.sunm2n.pay.payment.domain.OrderIds;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
   *
   * <p>{@code Idempotency-Key} 는 선택이다. <b>전달하지 않은 경우만</b> 기존 경로이고, 빈 값·형식 오류는 400 이다 (4.1). 같은 헤더가
   * 여러 번 오면 값이 쉼표로 합쳐져 {@code String} 으로 들어오고, 쉼표 금지 규칙에 걸린다.
   */
  @PostMapping("/{paymentKey}/cancel")
  public ResponseEntity<PaymentSnapshot> cancel(
      @PathVariable String paymentKey,
      @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Valid @RequestBody CancelPaymentRequest request) {
    CancelOutcome outcome =
        cancelCoordinator.cancel(
            merchantContext.merchantId(),
            idempotencyKey == null ? null : IdempotencyKeys.validate(idempotencyKey),
            paymentKey,
            request.cancelAmount(),
            request.reason());
    return ResponseEntity.status(outcome.status()).body(outcome.body());
  }

  @GetMapping("/{paymentKey}")
  public PaymentResponse get(@PathVariable String paymentKey) {
    return PaymentResponse.from(paymentService.get(merchantContext.merchantId(), paymentKey));
  }

  /**
   * 주문 id 로 조회한다 (S6). 잠그지 않는다. 경로 변수는 생성 요청과 같은 {@link OrderIds} 규칙으로 먼저 검증하고, 규칙 밖이면 조회하지 않고 400
   * 이다.
   *
   * <p>{@code GET /{paymentKey}} 와 겹치지 않는다 — 그쪽은 세그먼트가 하나다 ({@code docs/plan/S6.md} 4.1).
   */
  @GetMapping("/orders/{orderId}")
  public PaymentResponse getByOrder(@PathVariable String orderId) {
    return PaymentResponse.from(
        paymentService.getByOrder(merchantContext.merchantId(), OrderIds.validate(orderId)));
  }

  /**
   * 주문 id 로 취소한다 (S6). {@code merchant_id} 와 {@code order_id} 로 잠그며 읽은 뒤 취소한다. 본문과 {@code
   * Idempotency-Key} 규칙은 {@link #cancel} 과 같다.
   *
   * <p>검증 순서: 본문(인자 해석 단계) → 주문 id 형식 → 멱등키 형식. 어느 쪽이든 실패하면 조회·취소를 실행하지 않는다.
   */
  @PostMapping("/orders/{orderId}/cancel")
  public ResponseEntity<PaymentSnapshot> cancelByOrder(
      @PathVariable String orderId,
      @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Valid @RequestBody CancelPaymentRequest request) {
    String validOrderId = OrderIds.validate(orderId);
    CancelOutcome outcome =
        cancelCoordinator.cancelByOrder(
            merchantContext.merchantId(),
            idempotencyKey == null ? null : IdempotencyKeys.validate(idempotencyKey),
            validOrderId,
            request.cancelAmount(),
            request.reason());
    return ResponseEntity.status(outcome.status()).body(outcome.body());
  }
}
