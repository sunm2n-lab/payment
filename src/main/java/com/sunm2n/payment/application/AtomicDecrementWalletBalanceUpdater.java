package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.LedgerType;
import com.sunm2n.payment.domain.Wallet;
import com.sunm2n.payment.domain.WalletLedger;
import com.sunm2n.payment.domain.exception.InsufficientBalanceException;
import com.sunm2n.payment.infrastructure.WalletLedgerRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;

/**
 * S2 비교 실험 1 — 검사한 뒤 원자적으로 감산한다 (SCENARIO 139행).
 *
 * <pre>UPDATE wallet SET balance = balance - ? WHERE id = ?</pre>
 *
 * <p>읽은 값이 아니라 DB 의 현재 값에서 빼므로 <b>차감 유실은 사라진다.</b> 잔액과 원장 합계도 일치한다. 그런데 잔액 검사는 여전히 이 문장 밖에 있어서, 검사
 * 시점에 모두 10,000 을 본 4건이 전부 감산에 성공해 <b>잔액이 −2,000 이 된다.</b> 불변식 1 의 두 조건("합계 일치"와 "음수 아님") 중 하나만 깨지는
 * 것이고, Phase 0 이 {@code UNSIGNED}/{@code CHECK} 를 일부러 넣지 않은 이유가 여기서 회수된다.
 *
 * <p>{@link Wallet} 엔티티를 <b>로드하지 않는다.</b> 이유는 {@code WalletRepository.findBalanceById} 주석에 있다.
 *
 * <p>충전은 관찰 대상이 아니므로 naive 에 위임한다.
 */
public class AtomicDecrementWalletBalanceUpdater implements WalletBalanceUpdater {

  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;
  private final WalletBalanceUpdater creditDelegate;

  public AtomicDecrementWalletBalanceUpdater(
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      WalletBalanceUpdater creditDelegate) {
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
    this.creditDelegate = creditDelegate;
  }

  @Override
  public void debit(Long walletId, Long paymentId, long amount) {
    long balance =
        walletRepository
            .findBalanceById(walletId)
            .orElseThrow(() -> new IllegalStateException("결제에 연결된 지갑이 없습니다. walletId=" + walletId));

    // 검사와 감산 사이에 원자성이 없다. 여기서 통과한 요청은 전부 감산에 성공한다.
    if (balance < amount) {
      throw new InsufficientBalanceException(walletId, balance, amount);
    }

    walletRepository.decreaseBalance(walletId, amount);
    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.PAY, -amount, paymentId));
  }

  @Override
  public Wallet credit(Long memberId, long amount) {
    return creditDelegate.credit(memberId, amount);
  }
}
