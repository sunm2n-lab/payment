package com.sunm2n.pay.api.dto;

import com.sunm2n.pay.domain.Wallet;

public record WalletResponse(Long memberId, long balance) {

  public static WalletResponse from(Wallet wallet) {
    return new WalletResponse(wallet.getMemberId(), wallet.getBalance());
  }
}
