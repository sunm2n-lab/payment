package com.sunm2n.payment.domain.exception;

/** 지갑 잔액이 결제 금액보다 적다. */
public class InsufficientBalanceException extends DomainException {

  public InsufficientBalanceException(Long walletId, long balance, long amount) {
    super("잔액이 부족합니다. walletId=" + walletId + ", balance=" + balance + ", amount=" + amount);
  }
}
