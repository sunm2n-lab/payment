package com.sunm2n.payment.application;

import com.sunm2n.payment.infrastructure.PaymentRepository;
import com.sunm2n.payment.infrastructure.WalletLedgerRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 동시성 전략의 조합을 한곳에 모은다.
 *
 * <p>실패 버전과 개선 버전을 코드에 공존시키는 것이 이 교재의 규약이다 ({@code docs/plan/PHASE0.md} 2절). S1 에서는 갈래가 승인 구현
 * <b>둘</b> 이라 {@code @Service} + {@code @Primary} 로 충분했지만, S2 는 (승인 구현 × 잔액 변경 전략) 조합이 늘어난다. 어떤 조합이
 * 존재하는지가 여러 클래스의 애너테이션에 흩어지면 실습에서 무엇을 실행 중인지 읽어 낼 수 없으므로, 조합을 이 표 하나로 옮긴다.
 *
 * <p><b>조합을 빈으로 등록하는 이유</b>는 {@code @Transactional} 프록시다. 프록시는 컨테이너가 만들므로, 테스트가 손으로 조립한 confirmer
 * 에는 트랜잭션 경계가 생기지 않는다. 반면 전략(updater) 자체는 호출자의 트랜잭션에 참여할 뿐이라 빈일 필요가 없다 — 그럼에도 빈으로 두는 것은 조합을 여기 모으기
 * 위해서다.
 */
@Configuration
public class ConcurrencyStrategyConfig {

  /** Phase 0 그대로의 잔액 변경. S1·S2 재현이 이 전략을 쓴다. */
  @Bean
  NaiveWalletBalanceUpdater naiveWalletBalanceUpdater(
      WalletRepository walletRepository, WalletLedgerRepository walletLedgerRepository) {
    return new NaiveWalletBalanceUpdater(walletRepository, walletLedgerRepository);
  }

  /** S1 재현용 — naive 승인 + naive 차감. 기존 {@code naivePaymentConfirmer} 빈 이름을 유지한다. */
  @Bean
  PaymentConfirmer naivePaymentConfirmer(
      PaymentSupport support, NaiveWalletBalanceUpdater naiveUpdater) {
    return new NaivePaymentConfirmer(support, naiveUpdater);
  }

  /**
   * S2 재현용 — CAS 승인 + naive 차감. S2 의 전제가 "S1 적용"(SCENARIO 134행)이므로 상태 전이는 이미 원자적이고, 남은 결함은 잔액
   * 차감뿐이다.
   *
   * <p>본선({@link #casPaymentConfirmer})과 따로 두는 이유는 본선의 전략이 곧 바뀌기 때문이다. 재현이 본선 빈을 가리키고 있으면 본선을 개선하는
   * 순간 재현 테스트가 사라진다.
   */
  @Bean
  PaymentConfirmer naiveDebitConfirmer(
      PaymentSupport support,
      PaymentRepository paymentRepository,
      NaiveWalletBalanceUpdater naiveUpdater) {
    return new CasPaymentConfirmer(support, paymentRepository, naiveUpdater);
  }

  /**
   * 본선 승인. {@link PaymentService} 가 이 빈에 위임한다.
   *
   * <p>차감 전략은 아직 naive 다 — S2 의 차감 유실이 그대로 남아 있다. 비관적 락으로 바꾸는 것이 S2-b 다.
   */
  @Bean
  @Primary
  PaymentConfirmer casPaymentConfirmer(
      PaymentSupport support,
      PaymentRepository paymentRepository,
      NaiveWalletBalanceUpdater naiveUpdater) {
    return new CasPaymentConfirmer(support, paymentRepository, naiveUpdater);
  }

  /** 본선 충전. {@code WalletController} 와 대부분의 테스트가 이 빈을 쓴다. 전략은 승인과 같은 이유로 아직 naive 다. */
  @Bean
  @Primary
  WalletService walletService(
      WalletRepository walletRepository, NaiveWalletBalanceUpdater naiveUpdater) {
    return new WalletService(walletRepository, naiveUpdater);
  }

  /** 충전 경쟁 재현용. 본선이 바뀌어도 이 빈은 Phase 0 의 충전 경로를 유지한다. */
  @Bean
  WalletService naiveWalletService(
      WalletRepository walletRepository, NaiveWalletBalanceUpdater naiveUpdater) {
    return new WalletService(walletRepository, naiveUpdater);
  }
}
