package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.Wallet;

/**
 * 지갑 잔액 변경 전략.
 *
 * <p>S2 는 잔액을 바꾸는 방식이 다섯으로 갈라진다 (naive / 원자적 감산 / 조건부 감산 / 낙관적 락 / 비관적 락). 갈라지는 지점은 승인의 MONEY 분기와
 * 충전 둘뿐이고 나머지는 같아야 하므로, 그 둘만 이 이음매로 뽑아낸다. {@link PaymentConfirmer} 가 "상태 전이를 어떻게 확정하느냐" 하나로 갈렸던 것과
 * 같은 방식이다.
 *
 * <p>전략은 <b>호출자가 넘긴다.</b> 구현 하나가 가변 상태로 전략을 바꾸는 방식은 쓰지 않는다. 조합은 {@link ConcurrencyStrategyConfig} 에
 * 모여 있다.
 *
 * <p>스스로 트랜잭션을 열지 않는다. 호출자의 트랜잭션에 참여한다. 잔액 변경과 원장 INSERT 가 한 트랜잭션이어야 "잔액 == 원장 합계" 가 유지된다.
 */
public interface WalletBalanceUpdater {

  /**
   * 승인 차감. PAY 원장까지 남긴다.
   *
   * <p>지갑을 {@code walletId} 로 받는다. 결제 생성 시점에 확정된 {@code payment.wallet_id} 다.
   *
   * @throws com.sunm2n.payment.domain.exception.InsufficientBalanceException 잔액이 부족할 때
   */
  void debit(Long walletId, Long paymentId, long amount);

  /**
   * 충전. CHARGE 원장까지 남기고 바뀐 지갑을 돌려준다.
   *
   * <p>차감과 달리 {@code memberId} 로 받는다. 충전 요청에는 지갑 식별자가 없고, 지갑을 <b>어떻게 찾느냐</b>부터 전략마다 다르기 때문이다. naive
   * 는 잠금 없이 잔액까지 읽고(재현 경로), 비관적 락은 id 만 찾은 뒤 {@code FOR UPDATE} 로 잠그며 읽는다. 찾는 방법을 호출자가 정해 버리면 후자가
   * 잠그기 전에 이미 읽은 엔티티를 들고 있게 된다.
   *
   * <p>{@link Wallet} 을 돌려주는 이유는 {@code WalletController} 가 응답에 {@code memberId}·{@code balance} 를
   * 담기 때문이다.
   */
  Wallet credit(Long memberId, long amount);
}
