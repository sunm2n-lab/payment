package com.sunm2n.pay.wallet.domain;

import com.sunm2n.pay.wallet.domain.exception.BalanceOverflowException;

/**
 * 금액 산술.
 *
 * <p><b>덧셈에만 쓴다.</b> 뺄셈에는 의도적으로 적용하지 않는다. 결제 승인의 잔액 차감이 음수를 만들 수 있어야 S2 의 Lost Update 실습(잔액 -2,000
 * 관찰)이 성립하기 때문이다. 여기서 막아야 하는 것은 "관찰 대상인 음수"가 아니라 "오버플로로 생긴 가짜 음수" 다. 둘이 섞이면 S2 에서 원인을 구분할 수 없다.
 */
public final class Amounts {

  private Amounts() {}

  /** 오버플로면 {@link BalanceOverflowException} 으로 바꿔 4xx 로 응답되게 한다. */
  public static long add(long current, long amount) {
    try {
      return Math.addExact(current, amount);
    } catch (ArithmeticException e) {
      throw new BalanceOverflowException(current, amount);
    }
  }
}
