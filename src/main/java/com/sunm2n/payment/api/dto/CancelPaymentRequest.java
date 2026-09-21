package com.sunm2n.payment.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CancelPaymentRequest(@Positive long cancelAmount, @Size(max = 255) String reason) {}
