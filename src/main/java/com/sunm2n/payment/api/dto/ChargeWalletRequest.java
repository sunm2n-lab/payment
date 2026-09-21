package com.sunm2n.payment.api.dto;

import jakarta.validation.constraints.Positive;

public record ChargeWalletRequest(@Positive long amount) {}
