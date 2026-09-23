package com.sunm2n.pay.wallet.application.balance;

import com.sunm2n.pay.wallet.domain.Amounts;
import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.domain.WalletLedger;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.wallet.domain.exception.WalletNotFoundException;
import com.sunm2n.pay.wallet.infrastructure.WalletLedgerRepository;
import com.sunm2n.pay.wallet.infrastructure.WalletRepository;

/**
 * S2-b 비관적 락 — <b>본선</b> (SCENARIO 144~145행).
 *
 * <pre>SELECT ... FROM wallet WHERE id = ? FOR UPDATE</pre>
 *
 * <p>읽는 순간 X 락을 잡고 트랜잭션 종료까지 유지한다. 그래서 "읽고 -> 검사하고 -> 계산한 값을 저장한다" 라는 코드 모양을 <b>그대로 두고도</b> 유실이
 * 사라진다. 뒤이어 온 요청은 앞선 요청이 커밋한 잔액을 보고 검사한다.
 *
 * <p>조건부 감산(비교 실험 2)도 유효한 해결책이지만 본선은 이쪽이다. 검사할 것이 늘어나도 같은 자리에서 표현되고, 취소·정산까지 같은 규칙으로 묶을 수 있으며,
 * {@code payment → wallet} 선행 잠금이 S4 의 FK 락 승격 데드락을 예방한다.
 *
 * <p>충전도 같은 순서를 따른다. <b>id 만 먼저 찾고</b>, 잔액은 잠그며 읽는다. 지갑을 평범하게 읽어 둔 뒤 같은 id 를 {@code FOR UPDATE} 로
 * 조회하면 영속성 컨텍스트의 낡은 엔티티가 그대로 돌아와, 락은 잡았는데 값은 잠그기 전의 것이 된다.
 */
public class PessimisticLockWalletBalanceUpdater implements WalletBalanceUpdater {

  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;

  public PessimisticLockWalletBalanceUpdater(
      WalletRepository walletRepository, WalletLedgerRepository walletLedgerRepository) {
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
  }

  @Override
  public void debit(Long walletId, Long paymentId, long amount) {
    Wallet wallet = lock(walletId);

    if (wallet.getBalance() < amount) {
      throw new InsufficientBalanceException(walletId, wallet.getBalance(), amount);
    }
    wallet.setBalance(wallet.getBalance() - amount);
    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.PAY, -amount, paymentId));
  }

  @Override
  public Wallet credit(Long memberId, long amount) {
    Long walletId =
        walletRepository
            .findIdByMemberId(memberId)
            .orElseThrow(() -> new WalletNotFoundException(memberId));
    Wallet wallet = lock(walletId);

    wallet.setBalance(Amounts.add(wallet.getBalance(), amount));
    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.CHARGE, amount, null));

    return wallet;
  }

  private Wallet lock(Long walletId) {
    return walletRepository
        .findByIdForUpdate(walletId)
        .orElseThrow(() -> new IllegalStateException("결제에 연결된 지갑이 없습니다. walletId=" + walletId));
  }
}
