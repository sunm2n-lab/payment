package com.sunm2n.pay.wallet.api.dto;

import jakarta.validation.constraints.Positive;

public record ChargeWalletRequest(@Positive long amount) {}
