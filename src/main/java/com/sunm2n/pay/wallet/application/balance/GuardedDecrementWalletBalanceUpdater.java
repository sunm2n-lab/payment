package com.sunm2n.pay.wallet.application.balance;

import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.domain.WalletLedger;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.wallet.infrastructure.WalletLedgerRepository;
import com.sunm2n.pay.wallet.infrastructure.WalletRepository;

/**
 * S2 비교 실험 2 — 검사와 감산을 한 문장으로 원자화한다 (SCENARIO 140행).
 *
 * <pre>UPDATE wallet SET balance = balance - ? WHERE id = ? AND balance >= ?</pre>
 *
 * <p>실험 1 이 남긴 음수 잔액은 검사가 감산 밖에 있어서 생겼다. 검사를 WHERE 로 들여보내면 그 틈이 사라진다. 갱신 건수가 1 인 요청만 승인을 진행하고, 나머지는
 * 잔액 부족으로 거절된다 — S1 이 상태 전이에 쓴 방법과 같은 모양이다.
 *
 * <p>성공 경로에서는 <b>지갑을 읽지 않는다.</b> 검사가 UPDATE 안에 있으므로 읽을 이유가 없고, 읽지 않으므로 오래된 엔티티도 생기지 않는다. 대신 거절할 때
 * 잔액을 주장할 수 없다 ({@link InsufficientBalanceException#notEnough}).
 *
 * <p>충전은 관찰 대상이 아니므로 naive 에 위임한다.
 */
public class GuardedDecrementWalletBalanceUpdater implements WalletBalanceUpdater {

  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;
  private final WalletBalanceUpdater creditDelegate;

  public GuardedDecrementWalletBalanceUpdater(
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      WalletBalanceUpdater creditDelegate) {
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
    this.creditDelegate = creditDelegate;
  }

  @Override
  public void debit(Long walletId, Long paymentId, long amount) {
    if (walletRepository.decreaseBalanceIfEnough(walletId, amount) == 0) {
      // 0 건의 이유는 둘이다 - 잔액이 모자라거나 지갑이 아예 없거나. 다른 전략은 지갑이 없을 때 데이터
      // 불일치(5xx)로 끝내므로, 구분하지 않으면 이 전략만 실패의 의미가 달라진다. 실패 경로에서만 한 번 더
      // 묻고, 잔액은 읽지 않는다 - 존재 여부만 필요하다.
      if (!walletRepository.existsById(walletId)) {
        throw new IllegalStateException("결제에 연결된 지갑이 없습니다. walletId=" + walletId);
      }
      throw InsufficientBalanceException.notEnough(walletId, amount);
    }
    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.PAY, -amount, paymentId));
  }

  @Override
  public Wallet credit(Long memberId, long amount) {
    return creditDelegate.credit(memberId, amount);
  }
}
