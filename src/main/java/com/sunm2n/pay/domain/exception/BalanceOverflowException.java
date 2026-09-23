package com.sunm2n.pay.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/**
 * 잔액 덧셈이 {@code long} 범위를 넘는다.
 *
 * <p>막지 않으면 순차 요청만으로 잔액이 음수가 되어 "잔액 == 원장 합계" 불변식이 깨진다. 그 음수는 S2 의 Lost Update 가 만든 음수와 구분되지 않으므로,
 * 동시성 실습의 관찰을 오염시킨다.
 */
public class BalanceOverflowException extends DomainException {

  public BalanceOverflowException(long balance, long amount) {
    super("잔액이 처리 가능한 범위를 넘습니다. balance=" + balance + ", amount=" + amount);
  }
}
