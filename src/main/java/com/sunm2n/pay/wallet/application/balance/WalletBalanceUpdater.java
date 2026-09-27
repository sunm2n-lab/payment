package com.sunm2n.pay.wallet.application.balance;

import com.sunm2n.pay.wallet.domain.Wallet;

/**
 * 지갑 잔액 변경 전략.
 *
 * <p>S2 는 잔액을 바꾸는 방식이 다섯으로 갈라진다 (naive / 원자적 감산 / 조건부 감산 / 낙관적 락 / 비관적 락). 갈라지는 지점은 승인의 MONEY 분기와
 * 충전, 그리고 S3 에서 더한 취소 환불이고 나머지는 같아야 하므로, 그 지점만 이 이음매로 뽑아낸다. {@link
 * com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer} 가 "상태 전이를 어떻게 확정하느냐" 하나로 갈렸던 것과
 * 같은 방식이다.
 *
 * <p>전략은 <b>호출자가 넘긴다.</b> 구현 하나가 가변 상태로 전략을 바꾸는 방식은 쓰지 않는다. 조합은 {@link
 * com.sunm2n.pay.bootstrap.config.ConcurrencyStrategyConfig} 에 모여 있다.
 *
 * <p>스스로 트랜잭션을 열지 않는다. 호출자의 트랜잭션에 참여한다. 잔액 변경과 원장 INSERT 가 한 트랜잭션이어야 "잔액 == 원장 합계" 가 유지된다.
 */
public interface WalletBalanceUpdater {

  /**
   * 승인 차감. PAY 원장까지 남긴다.
   *
   * <p>지갑을 {@code walletId} 로 받는다. 결제 생성 시점에 확정된 {@code payment.wallet_id} 다.
   *
   * @throws com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException 잔액이 부족할 때
   */
  void debit(Long walletId, Long paymentId, long amount);

  /**
   * 충전. CHARGE 원장까지 남기고 바뀐 지갑을 돌려준다.
   *
   * <p>차감과 달리 {@code memberId} 로 받는다. 충전 요청에는 지갑 식별자가 없고, 지갑을 <b>어떻게 찾느냐</b>부터 전략마다 다르기 때문이다. naive
   * 는 잠금 없이 잔액까지 읽고(재현 경로), 비관적 락은 id 만 찾은 뒤 {@code FOR UPDATE} 로 잠그며 읽는다. 호출자가 스칼라 id 만 조회해 넘기는
   * 방식도 가능하지만, 그러면 두 조회 경로가 서비스와 전략에 나뉜다 — 한곳에 모으려는 선택이다.
   *
   * <p>호출자가 <b>엔티티를</b> 먼저 로드해 넘기는 방식만은 피해야 한다. 그 뒤 {@code FOR UPDATE} 로 같은 id 를 조회해도 영속성 컨텍스트에 이미
   * 있는 엔티티는 상태가 갱신되지 않아, 락은 잡았는데 값은 잠그기 전의 것이 된다.
   *
   * <p>{@link Wallet} 을 돌려주는 이유는 {@code WalletController} 가 응답에 {@code memberId}·{@code balance} 를
   * 담기 때문이다.
   */
  Wallet credit(Long memberId, long amount);

  /**
   * 취소 환불. REFUND 원장까지 남긴다.
   *
   * <p>S3 에서 결제 쪽에 있던 환불 코드를 이리로 옮겼다. 차감·충전과 <b>같은 전략</b>으로 묶는 것이 요점이다. 같은 {@code wallet} 행을 바꾸는 경로
   * 하나라도 잠금 없이 읽고 쓰면, 다른 경로가 {@code FOR UPDATE} 로 잠가 둔 효과가 사라진다 — 잠금 없이 읽은 쪽이 나중에 옛 값으로 계산한 절대값을
   * 덮어쓴다. 환불 전략을 따로 고를 수 있게 두면 "차감은 비관적, 환불은 naive" 같은 틀린 조합이 가능해진다.
   *
   * <p>{@link #credit} 과 합치지 않는다. 지갑을 찾는 키(walletId / memberId), 원장 타입(REFUND / CHARGE), {@code
   * paymentId} 유무가 모두 다르다. {@code paymentId} 는 {@code Long} 이라 {@code wallet -> payment} import 가
   * 생기지 않는다.
   *
   * @throws com.sunm2n.pay.wallet.domain.exception.BalanceOverflowException 잔액이 long 범위를 넘을 때
   */
  void refund(Long walletId, Long paymentId, long amount);
}
