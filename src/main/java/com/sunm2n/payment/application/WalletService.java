package com.sunm2n.payment.application;

import com.sunm2n.payment.domain.Amounts;
import com.sunm2n.payment.domain.LedgerType;
import com.sunm2n.payment.domain.Wallet;
import com.sunm2n.payment.domain.WalletLedger;
import com.sunm2n.payment.domain.exception.WalletNotFoundException;
import com.sunm2n.payment.infrastructure.WalletLedgerRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 머니 지갑.
 *
 * <p>Phase 0 의 절대 규칙은 "읽고 -> 검사하고 -> 계산한 값을 저장한다" 다 (SCENARIO 24행). 잔액 변경은 반드시 {@code
 * setBalance(getBalance() +- amount)} 로 JPA 더티체킹에 맡긴다. {@code UPDATE wallet SET balance = balance -
 * ?} 같은 원자적 감산을 여기서 쓰면 S2 의 Lost Update 실습이 사라진다.
 *
 * <p>조회 -> 잔액 변경 -> 원장 INSERT 는 반드시 한 서비스 트랜잭션 안이어야 한다. {@code open-in-view: false} 라 트랜잭션이 없으면
 * repository 호출이 끝나는 순간 지갑 엔티티가 detached 되어 더티체킹이 일어나지 않고, 원장만 INSERT 되어 정상 흐름에서도 "잔액 == 원장 합계" 가
 * 깨진다.
 *
 * <p>트랜잭션으로 묶어도 REPEATABLE READ 의 일반 SELECT 는 잠금이 없으므로 S2 의 Lost Update(충전 vs 결제 경쟁 포함)는 그대로 보존된다.
 */
@Service
public class WalletService {

  private final WalletRepository walletRepository;
  private final WalletLedgerRepository walletLedgerRepository;

  public WalletService(
      WalletRepository walletRepository, WalletLedgerRepository walletLedgerRepository) {
    this.walletRepository = walletRepository;
    this.walletLedgerRepository = walletLedgerRepository;
  }

  @Transactional
  public Wallet charge(Long memberId, long amount) {
    Wallet wallet = findWallet(memberId);

    wallet.setBalance(Amounts.add(wallet.getBalance(), amount));
    walletLedgerRepository.save(new WalletLedger(wallet.getId(), LedgerType.CHARGE, amount, null));

    return wallet;
  }

  @Transactional(readOnly = true)
  public Wallet get(Long memberId) {
    return findWallet(memberId);
  }

  private Wallet findWallet(Long memberId) {
    return walletRepository
        .findByMemberId(memberId)
        .orElseThrow(() -> new WalletNotFoundException(memberId));
  }
}
