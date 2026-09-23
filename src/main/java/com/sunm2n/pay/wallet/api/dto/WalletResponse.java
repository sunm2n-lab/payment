package com.sunm2n.pay.wallet.api.dto;

import com.sunm2n.pay.wallet.domain.Wallet;

public record WalletResponse(Long memberId, long balance) {

  public static WalletResponse from(Wallet wallet) {
    return new WalletResponse(wallet.getMemberId(), wallet.getBalance());
  }
}
