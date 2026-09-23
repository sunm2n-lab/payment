package com.sunm2n.pay.application;

import com.sunm2n.pay.domain.Amounts;
import com.sunm2n.pay.domain.LedgerType;
import com.sunm2n.pay.domain.Wallet;
import com.sunm2n.pay.domain.WalletLedger;
import com.sunm2n.pay.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.domain.exception.WalletNotFoundException;
import com.sunm2n.pay.infrastructure.WalletLedgerRepository;
import com.sunm2n.pay.infrastructure.WalletRepository;

/**
 * Phase 0 의 잔액 변경 — 결함을 품은 구현.
 *
 * <p>절대 규칙 그대로다: "읽고 -> 검사하고 -> 계산한 값을 저장한다" (SCENARIO 24행). 읽기는 잠금 없는 consistent read 라 경쟁하는 요청이
 * 모두 같은 잔액을 읽고, flush 시점의 {@code UPDATE wallet SET balance = <절대값>} 이 서로를 덮어쓴다 — S2 의 재현 대상인
 * read-modify-write Lost Update 다.
 *
 * <p>개선 후에도 남겨 둔다. 재현 테스트가 이 구현을 직접 골라 과거의 실패를 계속 실행한다.
 */
public class NaiveWalletBalanceUpdater implements WalletBalanceUpdater {

  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;

  public NaiveWalletBalanceUpdater(
      WalletRepository walletRepository, WalletLedgerRepository walletLedgerRepository) {
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
  }

  @Override
  public void debit(Long walletId, Long paymentId, long amount) {
    Wallet wallet = load(walletId);

    if (wallet.getBalance() < amount) {
      throw new InsufficientBalanceException(walletId, wallet.getBalance(), amount);
    }
    // 차감에는 Amounts 를 쓰지 않는다. 음수 잔액은 S2 비교 실험 1 의 관찰 대상이다.
    wallet.setBalance(wallet.getBalance() - amount);
    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.PAY, -amount, paymentId));
  }

  @Override
  public Wallet credit(Long memberId, long amount) {
    Wallet wallet =
        walletRepository
            .findByMemberId(memberId)
            .orElseThrow(() -> new WalletNotFoundException(memberId));

    wallet.setBalance(Amounts.add(wallet.getBalance(), amount));
    walletLedgerRepository.save(new WalletLedger(wallet.getId(), LedgerType.CHARGE, amount, null));

    return wallet;
  }

  /** 결제 생성 시점에 확정된 wallet_id 다. 여기서 없다면 데이터 불일치이므로 예상 밖 오류(5xx)로 둔다. */
  private Wallet load(Long walletId) {
    return walletRepository
        .findById(walletId)
        .orElseThrow(() -> new IllegalStateException("결제에 연결된 지갑이 없습니다. walletId=" + walletId));
  }
}
