package com.sunm2n.payment.api.dto;

import jakarta.validation.constraints.Positive;

public record CancelPaymentRequest(@Positive long cancelAmount, String reason) {}
