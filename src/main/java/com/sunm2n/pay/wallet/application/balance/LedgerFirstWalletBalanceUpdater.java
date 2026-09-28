package com.sunm2n.pay.wallet.application.balance;

import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.domain.WalletLedger;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.wallet.infrastructure.WalletLedgerRepository;
import com.sunm2n.pay.wallet.infrastructure.WalletRepository;

/**
 * S4 실패 구현 — wallet 선행 잠금 없이 <b>원장을 먼저</b> INSERT 하고 지갑을 나중에 갱신한다 (SCENARIO 162~165행).
 *
 * <pre>
 * SELECT balance FROM wallet WHERE id = ?                 잠그지 않는다
 * INSERT INTO wallet_ledger (wallet_id, ...) VALUES (?)   FK 검사 → wallet 행 S 락
 * UPDATE wallet SET balance = balance - ? WHERE id = ?     S → X 승격이 필요하다
 * </pre>
 *
 * <p>같은 지갑의 서로 다른 결제 둘이 INSERT 까지 마치면 두 S 락이 공존하고, 뒤이은 두 UPDATE 가 상대의 S 락을 기다려 순환 대기가 된다. InnoDB 는
 * 즉시 감지해 한쪽 트랜잭션 전체를 롤백한다(1213).
 *
 * <p>감산은 <b>원자적</b>이다. 읽은 값으로 계산한 절대값을 쓰면 Lost Update 가 섞여 실패 원인이 둘이 된다. 이 구현이 보여 주려는 것은 데드락 하나다.
 * 그래서 {@link AtomicDecrementWalletBalanceUpdater} 와 <b>같은 문장을 순서만 바꿔</b> 보낸다 — 그쪽은 UPDATE 가 먼저 X 락을
 * 잡으므로 데드락이 나지 않는다 (대조군). {@link Wallet} 엔티티는 로드하지 않는다. IDENTITY 원장의 즉시 INSERT 와 원자적 UPDATE 로 SQL
 * 순서가 코드 순서와 같아진다.
 *
 * <p>naive·낙관적 전략도 FK 아래에서는 같은 모양이 된다. 원장은 즉시 INSERT 되고 지갑 변경은 커밋 시점 flush 로 나가기 때문이다. 그 재현은 FK 가
 * 없는 V2 DB 에서 돈다 ({@code docs/plan/S4.md} 1.1).
 *
 * <p>충전과 환불은 관찰 대상이 아니므로 naive 에 위임한다.
 */
public class LedgerFirstWalletBalanceUpdater implements WalletBalanceUpdater {

  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;
  private final WalletBalanceUpdater naiveDelegate;

  public LedgerFirstWalletBalanceUpdater(
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      WalletBalanceUpdater naiveDelegate) {
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
    this.naiveDelegate = naiveDelegate;
  }

  @Override
  public void debit(Long walletId, Long paymentId, long amount) {
    long balance =
        walletRepository
            .findBalanceById(walletId)
            .orElseThrow(() -> new IllegalStateException("결제에 연결된 지갑이 없습니다. walletId=" + walletId));

    if (balance < amount) {
      throw new InsufficientBalanceException(walletId, balance, amount);
    }

    walletLedgerRepository.save(new WalletLedger(walletId, LedgerType.PAY, -amount, paymentId));
    walletRepository.decreaseBalance(walletId, amount);
  }

  @Override
  public Wallet credit(Long memberId, long amount) {
    return naiveDelegate.credit(memberId, amount);
  }

  @Override
  public void refund(Long walletId, Long paymentId, long amount) {
    naiveDelegate.refund(walletId, paymentId, amount);
  }
}
