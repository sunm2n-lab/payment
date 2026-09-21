package com.sunm2n.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** paymentKey / orderId / amount 세 값이 결제와 일치해야 승인된다. */
public record ConfirmPaymentRequest(
    @NotBlank String paymentKey, @NotBlank String orderId, @Positive long amount) {}
