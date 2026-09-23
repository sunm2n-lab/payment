package com.sunm2n.pay.bootstrap.config;

import com.sunm2n.pay.application.AtomicDecrementWalletBalanceUpdater;
import com.sunm2n.pay.application.CasPaymentConfirmer;
import com.sunm2n.pay.application.GuardedDecrementWalletBalanceUpdater;
import com.sunm2n.pay.application.NaivePaymentConfirmer;
import com.sunm2n.pay.application.NaiveWalletBalanceUpdater;
import com.sunm2n.pay.application.OptimisticLockWalletBalanceUpdater;
import com.sunm2n.pay.application.PaymentConfirmer;
import com.sunm2n.pay.application.PaymentService;
import com.sunm2n.pay.application.PaymentSupport;
import com.sunm2n.pay.application.PessimisticLockWalletBalanceUpdater;
import com.sunm2n.pay.application.RetryMetrics;
import com.sunm2n.pay.application.RetryingPaymentConfirmer;
import com.sunm2n.pay.application.WalletService;
import com.sunm2n.pay.infrastructure.PaymentRepository;
import com.sunm2n.pay.infrastructure.VersionedWalletRepository;
import com.sunm2n.pay.infrastructure.WalletLedgerRepository;
import com.sunm2n.pay.infrastructure.WalletRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
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

  /** S2 비교 실험 1 — 검사 후 원자적 감산. 차감 유실은 사라지지만 잔액이 음수가 된다. */
  @Bean
  AtomicDecrementWalletBalanceUpdater atomicDecrementWalletBalanceUpdater(
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      NaiveWalletBalanceUpdater naiveUpdater) {
    return new AtomicDecrementWalletBalanceUpdater(
        walletRepository, walletLedgerRepository, naiveUpdater);
  }

  /** 비교 실험 1 의 배선 — CAS 승인 + 원자적 감산. */
  @Bean
  PaymentConfirmer atomicDebitConfirmer(
      PaymentSupport support,
      PaymentRepository paymentRepository,
      AtomicDecrementWalletBalanceUpdater atomicUpdater) {
    return new CasPaymentConfirmer(support, paymentRepository, atomicUpdater);
  }

  /** S2 비교 실험 2 — 검사까지 UPDATE 안으로 넣은 조건부 감산. */
  @Bean
  GuardedDecrementWalletBalanceUpdater guardedDecrementWalletBalanceUpdater(
      WalletRepository walletRepository,
      WalletLedgerRepository walletLedgerRepository,
      NaiveWalletBalanceUpdater naiveUpdater) {
    return new GuardedDecrementWalletBalanceUpdater(
        walletRepository, walletLedgerRepository, naiveUpdater);
  }

  /** 비교 실험 2 의 배선 — CAS 승인 + 조건부 감산. */
  @Bean
  PaymentConfirmer guardedDebitConfirmer(
      PaymentSupport support,
      PaymentRepository paymentRepository,
      GuardedDecrementWalletBalanceUpdater guardedUpdater) {
    return new CasPaymentConfirmer(support, paymentRepository, guardedUpdater);
  }

  /** S2-a 낙관적 락. {@code VersionedWallet} 만 쓴다. */
  @Bean
  OptimisticLockWalletBalanceUpdater optimisticLockWalletBalanceUpdater(
      VersionedWalletRepository versionedWalletRepository,
      WalletLedgerRepository walletLedgerRepository,
      NaiveWalletBalanceUpdater naiveUpdater) {
    return new OptimisticLockWalletBalanceUpdater(
        versionedWalletRepository, walletLedgerRepository, naiveUpdater);
  }

  /**
   * S2-a 의 배선 — CAS 승인 + 낙관적 락. <b>트랜잭션 경계가 여기다.</b>
   *
   * <p>충돌은 이 경계의 커밋에서 터지므로, 재시도는 이 빈을 감싸는 {@link #retryingOptimisticDebitConfirmer} 가 맡는다.
   */
  @Bean
  PaymentConfirmer optimisticDebitConfirmer(
      PaymentSupport support,
      PaymentRepository paymentRepository,
      OptimisticLockWalletBalanceUpdater optimisticUpdater) {
    return new CasPaymentConfirmer(support, paymentRepository, optimisticUpdater);
  }

  @Bean
  RetryMetrics optimisticRetryMetrics() {
    return new RetryMetrics();
  }

  /**
   * S2-a 재시도. 트랜잭션 <b>바깥</b>이라 {@code @Transactional} 을 붙이지 않는다.
   *
   * <p>{@code maxAttempts = 4}(최초 호출 포함)의 근거: 버전을 올리는 것은 커밋에 성공한 차감뿐이고 잔액 10,000 / 건당 3,000 이면 성공은
   * 최대 3건이다. 따라서 어떤 워커도 최대 3회까지만 충돌할 수 있어 4번째 시도에서 반드시 판정이 난다.
   */
  @Bean
  PaymentConfirmer retryingOptimisticDebitConfirmer(
      @Qualifier("optimisticDebitConfirmer") PaymentConfirmer optimisticDebitConfirmer,
      RetryMetrics optimisticRetryMetrics) {
    return new RetryingPaymentConfirmer(
        optimisticDebitConfirmer,
        4,
        Duration.ofMillis(20),
        optimisticRetryMetrics,
        RetryingPaymentConfirmer.Sleeper.real());
  }

  /** S2-b 비관적 락 — 본선 전략. 승인과 충전이 같은 방식으로 지갑을 잠근다. */
  @Bean
  PessimisticLockWalletBalanceUpdater pessimisticLockWalletBalanceUpdater(
      WalletRepository walletRepository, WalletLedgerRepository walletLedgerRepository) {
    return new PessimisticLockWalletBalanceUpdater(walletRepository, walletLedgerRepository);
  }

  /**
   * 본선 승인. {@link PaymentService} 가 이 빈에 위임한다.
   *
   * <p>S2-b 의 결론을 적용해 차감 전략을 비관적 락으로 올렸다. 재현은 {@link #naiveDebitConfirmer} 가 계속 naive 를 가리키므로 여기를
   * 바꿔도 사라지지 않는다.
   */
  @Bean
  @Primary
  PaymentConfirmer casPaymentConfirmer(
      PaymentSupport support,
      PaymentRepository paymentRepository,
      PessimisticLockWalletBalanceUpdater pessimisticUpdater) {
    return new CasPaymentConfirmer(support, paymentRepository, pessimisticUpdater);
  }

  /** 본선 충전. {@code WalletController} 와 대부분의 테스트가 이 빈을 쓴다. */
  @Bean
  @Primary
  WalletService walletService(
      WalletRepository walletRepository, PessimisticLockWalletBalanceUpdater pessimisticUpdater) {
    return new WalletService(walletRepository, pessimisticUpdater);
  }

  /** 충전 경쟁 재현용. 본선이 바뀌어도 이 빈은 Phase 0 의 충전 경로를 유지한다. */
  @Bean
  WalletService naiveWalletService(
      WalletRepository walletRepository, NaiveWalletBalanceUpdater naiveUpdater) {
    return new WalletService(walletRepository, naiveUpdater);
  }
}
