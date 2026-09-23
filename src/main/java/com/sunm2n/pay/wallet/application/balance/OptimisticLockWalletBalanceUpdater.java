package com.sunm2n.pay.wallet.application.balance;

import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.VersionedWallet;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.domain.WalletLedger;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.wallet.infrastructure.VersionedWalletRepository;
import com.sunm2n.pay.wallet.infrastructure.WalletLedgerRepository;

/**
 * S2-a 낙관적 락 (SCENARIO 142행).
 *
 * <p>차감 코드는 naive 와 <b>같다.</b> 읽고, 검사하고, 계산한 절대값을 저장한다. 달라지는 것은 엔티티에 {@code @Version} 이 있다는 것
 * 하나뿐이고, 그래서 flush 시점의 UPDATE 가 {@code ... WHERE id=? AND version=?} 가 된다. 내가 읽은 뒤 누군가 커밋했다면 0 건이
 * 갱신되고 Hibernate 가 그것을 충돌로 보고한다.
 *
 * <p>충돌은 flush/commit 에서 터지고 <b>트랜잭션 전체가 롤백</b>된다. 그래서 재시도는 이 전략이 아니라 트랜잭션 <b>바깥</b>의 {@link
 * RetryingPaymentConfirmer} 가 맡는다. 여기서 잡아 다시 시도하면 이미 롤백이 예정된 트랜잭션 안에서 되돌리는 셈이다.
 *
 * <p>{@link VersionedWallet} 만 쓴다. 한 트랜잭션에서 {@link Wallet} 과 섞지 않는다 - 두 엔티티가 같은 행을 가리키므로 영속성 컨텍스트에
 * 같은 행의 상태가 둘 생긴다.
 *
 * <p>충전은 naive 에 위임한다. 낙관적 실험 중 충전은 사전 시딩만 하므로 관찰 대상이 아니다.
 */
public class OptimisticLockWalletBalanceUpdater implements WalletBalanceUpdater {

  private final VersionedWalletRepository versionedWalletRepository;
  private final WalletLedgerRepository walletLedgerRepository;
  private final WalletBalanceUpdater creditDelegate;

  public OptimisticLockWalletBalanceUpdater(
      VersionedWalletRepository versionedWalletRepository,
      WalletLedgerRepository walletLedgerRepository,
      WalletBalanceUpdater creditDelegate) {
    this.versionedWalletRepository = versionedWalletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
    this.creditDelegate = creditDelegate;
  }

  @Override
  public void debit(Long walletId, Long paymentId, long amount) {
    VersionedWallet wallet =
        versionedWalletRepository
            .findById(walletId)
            .orElseThrow(() -> new IllegalStateException("결제에 연결된 지갑이 없습니다. walletId=" + walletId));

    if (wallet.getBalance() < amount) {
      throw new InsufficientBalanceException(walletId, wallet.getBalance(), amount);
    }
    wallet.setBalance(wallet.getBalance() - amount);
    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.PAY, -amount, paymentId));
  }

  @Override
  public Wallet credit(Long memberId, long amount) {
    return creditDelegate.credit(memberId, amount);
  }
}
