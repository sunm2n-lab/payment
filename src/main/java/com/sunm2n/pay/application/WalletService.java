package com.sunm2n.pay.application;

import com.sunm2n.pay.domain.Wallet;
import com.sunm2n.pay.domain.exception.WalletNotFoundException;
import com.sunm2n.pay.infrastructure.WalletRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 머니 지갑.
 *
 * <p>충전의 잔액 변경은 {@link WalletBalanceUpdater} 가 소유한다. 승인 차감과 충전은 "지갑 잔액을 바꾸고 원장을 남긴다" 하나로 묶이고, S2 에서
 * 그 방식이 전략으로 갈라졌기 때문이다. 지갑을 <b>찾는 것까지</b> 전략에 맡긴다 — 잠금 없이 읽을지 {@code FOR UPDATE} 로 잠그며 읽을지가 전략의
 * 일부라, 조회 경로를 한곳에 모아 둔다.
 *
 * <p>조회 -> 잔액 변경 -> 원장 INSERT 는 반드시 한 서비스 트랜잭션 안이어야 한다. {@code open-in-view: false} 라 트랜잭션이 없으면
 * repository 호출이 끝나는 순간 지갑 엔티티가 detached 되어 더티체킹이 일어나지 않고, 원장만 INSERT 되어 정상 흐름에서도 "잔액 == 원장 합계" 가
 * 깨진다. 그 경계를 여기가 소유하므로 전략은 스스로 트랜잭션을 열지 않는다.
 *
 * <p>전략을 바꾼 빈을 여럿 등록한다 ({@link com.sunm2n.pay.bootstrap.config.ConcurrencyStrategyConfig}). 테스트 전용
 * 오버로드를 프로덕션 시그니처에 만들지 않는다.
 */
public class WalletService {

  private final WalletRepository walletRepository;
  private final WalletBalanceUpdater updater;

  public WalletService(WalletRepository walletRepository, WalletBalanceUpdater updater) {
    this.walletRepository = walletRepository;
    this.updater = updater;
  }

  @Transactional
  public Wallet charge(Long memberId, long amount) {
    return updater.credit(memberId, amount);
  }

  @Transactional(readOnly = true)
  public Wallet get(Long memberId) {
    return walletRepository
        .findByMemberId(memberId)
        .orElseThrow(() -> new WalletNotFoundException(memberId));
  }
}
