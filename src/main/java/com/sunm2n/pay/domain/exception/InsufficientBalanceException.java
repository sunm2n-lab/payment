package com.sunm2n.pay.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/** 지갑 잔액이 결제 금액보다 적다. */
public class InsufficientBalanceException extends DomainException {

  public InsufficientBalanceException(Long walletId, long balance, long amount) {
    super("잔액이 부족합니다. walletId=" + walletId + ", balance=" + balance + ", amount=" + amount);
  }

  private InsufficientBalanceException(Long walletId, long amount) {
    super("잔액이 부족합니다. walletId=" + walletId + ", amount=" + amount);
  }

  /**
   * 잔액을 주장하지 않는 거절. 조건부 UPDATE 로 탈락을 판정한 경로가 쓴다.
   *
   * <p>탈락자는 <b>아무 행도 바꾸지 않았고</b> REPEATABLE READ 스냅샷은 앞선 조회 시점에 고정돼 있다. 여기서 평범하게 다시 읽으면 승자들이 이미 커밋한
   * 잔액이 아니라 <b>처음 본 값</b>이 돌아온다. 그 값을 메시지에 담으면 거짓말이 된다 — S1 의 {@code lostConfirmRace} 와 같은 함정이다.
   *
   * <p>실제로 읽은 값을 아는 경로(naive / 원자적 감산 / 비관적 락)는 기존 생성자를 그대로 쓴다.
   */
  public static InsufficientBalanceException notEnough(Long walletId, long amount) {
    return new InsufficientBalanceException(walletId, amount);
  }
}
