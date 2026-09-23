package com.sunm2n.pay.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.domain.Payment;
import com.sunm2n.pay.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.wallet.application.WalletService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * S2-b 경합 관찰 (SCENARIO 143행) — 낙관적 락과 비관적 락을 같은 조건에서 돌려 본다.
 *
 * <p><b>잔액 부족과 락 충돌을 분리한다.</b> 회차마다 워커 수 × 결제 금액보다 넉넉한 잔액을 새로 심으므로, 모든 요청이 성공해야 한다. 한 번만 시딩하고 반복하면
 * 잔액이 줄어 결국 잔액 부족이 측정에 섞인다.
 *
 * <p><b>성능 수치에는 단언을 걸지 않는다.</b> CI 임계값으로 만들면 flaky 해진다. green 조건은 정합성 단언(전건 성공, 잔액 == 원장 합계)뿐이고,
 * 수치는 표로 남겨 {@code docs/phase1/S2.md} 에 옮긴다. 이 조건(단일 지갑, 워커 4, 로컬 컨테이너)의 관측이므로 일반적인 우열로 확대하지 않는다.
 *
 * <p>시딩과 정리는 계측에서 제외한다. 두 전략 모두 같은 동기화 지점({@code PAYMENT_READ})으로 출발을 맞춘 뒤, 경쟁 구간만 잰다.
 *
 * <p>낙관적 락은 <b>백오프를 바꿔 두 번</b> 잰다. 재시도 사이의 대기는 우리가 정한 값이므로, 그것을 빼지 않으면 "낙관적 락이 느리다" 가 아니라 "우리가 넣은
 * sleep 이 길다" 를 관측하고 끝난다. 백오프 합은 <b>설정값의 합</b>이고 워커별로 병렬로 겹치므로, 총 소요에서 빼는 식으로 쓰지 않는다 - 백오프 0 조건의 총
 * 소요와 견줘서 본다.
 */
class LockContentionObservationTest extends AbstractIntegrationTest {

  private static final int ROUNDS = 5;
  private static final int WORKERS = 4;
  private static final String ORDER_ID = "order-s2-contention";
  private static final long AMOUNT = 3_000L;

  /** 회차마다 새로 얹는 잔액. 워커 전원이 성공할 수 있도록 한 건 몫을 더 심어 잔액 부족을 측정에서 뺀다. */
  private static final long ROUND_BALANCE = (WORKERS + 1) * AMOUNT;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("retryingOptimisticDebitConfirmer")
  private PaymentConfirmer optimisticConfirmer;

  @Autowired
  @Qualifier("optimisticDebitConfirmer")
  private PaymentConfirmer optimisticDebitConfirmer;

  @Autowired
  @Qualifier("casPaymentConfirmer")
  private PaymentConfirmer pessimisticConfirmer;

  @Autowired
  @Qualifier("optimisticRetryMetrics")
  private RetryMetrics retryMetrics;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(com.sunm2n.pay.support.Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("같은 조건에서 낙관적/비관적 락을 N 회차 반복해 소요·지연·재시도를 기록한다")
  void measuresContentionOfBothStrategies() {
    // 재시도 데코레이터는 트랜잭션을 열지 않으므로 손으로 조립해도 된다. 백오프만 0 으로 바꿔 같은 delegate 를 감싼다.
    PaymentConfirmer withoutBackoff =
        new RetryingPaymentConfirmer(
            optimisticDebitConfirmer, 4, Duration.ZERO, retryMetrics, duration -> {});

    List<RoundStats> optimistic = measure("낙관적 락 (백오프 20ms)", optimisticConfirmer);
    List<RoundStats> noBackoff = measure("낙관적 락 (백오프 0)", withoutBackoff);
    List<RoundStats> pessimistic = measure("비관적 락", pessimisticConfirmer);

    print("낙관적 락 (백오프 20ms)", optimistic);
    print("낙관적 락 (백오프 0)", noBackoff);
    print("비관적 락", pessimistic);

    invariants.assertAll();
  }

  /** 회차마다 지갑과 결제를 새로 준비하고 경쟁 구간만 잰다. */
  private List<RoundStats> measure(String label, PaymentConfirmer confirmer) {
    List<RoundStats> rounds = new ArrayList<>(ROUNDS);
    for (int round = 0; round < ROUNDS; round++) {
      long memberId = (round % com.sunm2n.pay.support.Seeds.WALLET_COUNT) + 1L;
      walletService.charge(memberId, ROUND_BALANCE);
      long balanceBefore = balanceOf(memberId);
      Queue<String> paymentKeys = new ConcurrentLinkedQueue<>(createPayments(memberId));
      retryMetrics.reset();

      concurrencyGate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
      long startedAt = System.nanoTime();
      ConcurrentRunner.Results<Timed> results =
          ConcurrentRunner.run(WORKERS, () -> timed(confirmer, paymentKeys.poll()));
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

      assertThat(results.failures())
          .as("%s %d회차 - 잔액이 넉넉하므로 전건 성공해야 한다. 실패가 섞이면 측정이 아니라 관측 오류다", label, round + 1)
          .isEmpty();
      // 회차마다 잔액을 새로 얹으므로 절대값이 아니라 이번 회차의 차감량으로 본다.
      assertThat(balanceBefore - balanceOf(memberId))
          .as("%s %d회차 - 이번 회차의 차감 합계", label, round + 1)
          .isEqualTo(WORKERS * AMOUNT);

      rounds.add(
          new RoundStats(
              elapsed,
              results.successes().stream().map(Timed::latency).toList(),
              retryMetrics.conflicts(),
              retryMetrics.retries(),
              retryMetrics.exhausted(),
              retryMetrics.totalBackoff()));
    }
    return rounds;
  }

  private Timed timed(PaymentConfirmer confirmer, String paymentKey) {
    long startedAt = System.nanoTime();
    Payment confirmed = confirmer.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT);
    return new Timed(confirmed, Duration.ofNanos(System.nanoTime() - startedAt));
  }

  private List<String> createPayments(long memberId) {
    List<String> paymentKeys = new ArrayList<>(WORKERS);
    for (int i = 0; i < WORKERS; i++) {
      paymentKeys.add(
          paymentService
              .create(merchantId, ORDER_ID, AMOUNT, PaymentMethod.MONEY, memberId)
              .getPaymentKey());
    }
    return paymentKeys;
  }

  private void print(String label, List<RoundStats> rounds) {
    System.out.printf(
        "%n[S2 경합 관찰] %s - 워커 %d, 결제 %,d원, 회차 %d (단일 지갑)%n", label, WORKERS, AMOUNT, ROUNDS);
    System.out.println("회차 | 총 소요(ms) | 요청 지연 평균(ms) | 최대(ms) | 충돌 | 재시도 | 상한 초과 | 백오프 합(설정, ms)");
    for (int i = 0; i < rounds.size(); i++) {
      RoundStats stats = rounds.get(i);
      System.out.printf(
          "%4d | %11.1f | %17.1f | %8.1f | %4d | %6d | %9d | %10.1f%n",
          i + 1,
          millis(stats.elapsed()),
          stats.latencies().stream()
              .mapToDouble(LockContentionObservationTest::millis)
              .average()
              .orElse(0),
          stats.latencies().stream()
              .max(Comparator.naturalOrder())
              .map(LockContentionObservationTest::millis)
              .orElse(0d),
          stats.conflicts(),
          stats.retries(),
          stats.exhausted(),
          millis(stats.totalBackoff()));
    }
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / 1_000_000d;
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  /** 요청 하나의 결과와 그 요청이 걸린 시간. */
  private record Timed(Payment payment, Duration latency) {}

  private record RoundStats(
      Duration elapsed,
      List<Duration> latencies,
      long conflicts,
      long retries,
      long exhausted,
      Duration totalBackoff) {}
}
