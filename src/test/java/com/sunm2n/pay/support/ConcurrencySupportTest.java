package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동시성 테스트 자산 자체의 검증.
 *
 * <p>동기화 장치가 의도대로 동작하지 않으면 S1~S10 의 관측 결과를 믿을 수 없다. DB 가 필요 없으므로 컨테이너 없이 돈다.
 */
class ConcurrencySupportTest {

  private static final int WORKERS = 3;

  /** 직접 만든 워커를 풀어 준 뒤 종료를 기다리는 시간. */
  private static final Duration WORKER_STOP_GRACE = Duration.ofSeconds(5);

  @Test
  @DisplayName("무장하지 않은 게이트는 아무 일도 하지 않는다")
  void unarmedGateIsNoop() {
    ConcurrencyGate gate = new ConcurrencyGate();

    assertThatCode(() -> gate.pass(ConcurrencyGate.PAYMENT_READ)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("무장한 게이트는 참가자가 모두 모인 뒤에야 통과시킨다")
  void armedGateWaitsForEveryone() {
    ConcurrencyGate gate = new ConcurrencyGate();
    gate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    AtomicInteger arrived = new AtomicInteger();

    ConcurrentRunner.Results<Integer> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> {
              arrived.incrementAndGet();
              gate.pass(ConcurrencyGate.PAYMENT_READ);
              return arrived.get();
            });

    assertThat(results.failures()).isEmpty();
    assertThat(results.successes())
        .as("게이트를 통과한 시점에는 이미 모든 참가자가 도착해 있어야 한다")
        .containsExactly(WORKERS, WORKERS, WORKERS);
  }

  @Test
  @DisplayName("같은 스레드는 한 번만 대기한다 - 두 번째 호출이 다시 걸리면 아무도 오지 않아 멈춘다")
  void gatePassesEachThreadOnlyOnce() {
    ConcurrencyGate gate = new ConcurrencyGate();
    gate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);

    ConcurrentRunner.Results<String> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> {
              gate.pass(ConcurrencyGate.PAYMENT_READ);
              gate.pass(ConcurrencyGate.PAYMENT_READ);
              return "done";
            });

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);
  }

  @Test
  @DisplayName("호출 직전 게이트는 대상 메서드 본문에 들어가기 전에 참가자를 모은다")
  void beforeCallGateBlocksAheadOfTheMethodBody() throws Exception {
    ConcurrencyGate gate = new ConcurrencyGate();
    gate.arm(ConcurrencyGate.WALLET_LOCK_ATTEMPT, 2);
    AtomicInteger bodyStarted = new AtomicInteger();
    CountDownLatch bodyMayReturn = new CountDownLatch(1);

    // 잠그며 읽는 조회를 흉내 낸다. 한 번 들어가면 테스트가 풀어 줄 때까지 나오지 않는다 - 락을 쥔 채 머무는 것과 같다.
    LockingRead target =
        () -> {
          bodyStarted.incrementAndGet();
          bodyMayReturn.await();
          return "잠갔다";
        };
    LockingRead proxied =
        (LockingRead)
            ConcurrencyGateConfig.gated(
                target,
                LockingRead.class,
                () -> gate,
                Map.of("read", ConcurrencyGate.WALLET_LOCK_ATTEMPT),
                Map.of());

    AtomicReference<String> read = new AtomicReference<>();
    Thread worker = new Thread(() -> read.set(call(proxied)));
    worker.setDaemon(true);
    worker.start();

    try {
      // 반환 직후에 걸렸다면 워커는 본문 안에서 멈춰 있고 이 대기는 제한 시간까지 풀리지 않는다.
      gate.pass(ConcurrencyGate.WALLET_LOCK_ATTEMPT);
    } finally {
      // 이 워커는 직접 만든 스레드라 ConcurrentRunner 의 runaway 검사가 잡아 주지 않는다. 위가 실패하더라도
      // 본문 안에 남겨 두지 않고 여기서 풀어 준 뒤 실제 종료까지 확인한다.
      bodyMayReturn.countDown();
      worker.join(WORKER_STOP_GRACE.toMillis());
    }

    assertThat(worker.isAlive()).as("워커를 남기지 않는다").isFalse();
    assertThat(bodyStarted).as("게이트를 통과한 뒤에야 본문이 실행된다").hasValue(1);
    assertThat(read).hasValue("잠갔다");
  }

  @Test
  @DisplayName("해제한 게이트는 다시 무장 전 상태가 된다")
  void resetDisarmsGate() {
    ConcurrencyGate gate = new ConcurrencyGate();
    gate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    gate.reset();

    assertThatCode(() -> gate.pass(ConcurrencyGate.PAYMENT_READ)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("제한 시간 안에 끝나지 않으면 실패하고, 워커를 취소한 뒤 실제 종료까지 확인한다")
  void runnerCancelsAndConfirmsTerminationOnTimeout() {
    assertThatThrownBy(
            () ->
                ConcurrentRunner.run(
                    WORKERS,
                    Duration.ofSeconds(1),
                    () -> {
                      Thread.sleep(Duration.ofSeconds(20).toMillis());
                      return "끝나지 않는다";
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("끝나지 않았다");

    assertThatCode(ConcurrentRunner::verifyNoRunawayWorkers)
        .as("취소에 응답한 워커는 남지 않으므로 이후 테스트를 막지 않는다")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실행 결과는 성공과 실패를 모두 모으고 예상 밖 오류를 골라낼 수 있다")
  void runnerCollectsBothOutcomes() {
    AtomicInteger sequence = new AtomicInteger();

    ConcurrentRunner.Results<Integer> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> {
              int order = sequence.incrementAndGet();
              if (order == 1) {
                throw new IllegalStateException("예상한 실패");
              }
              if (order == 2) {
                throw new UnsupportedOperationException("예상 밖 오류");
              }
              return order;
            });

    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failureCount()).isEqualTo(2);
    assertThat(results.failuresOf(IllegalStateException.class)).hasSize(1);
    assertThat(results.failuresOtherThan(IllegalStateException.class))
        .singleElement()
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static String call(LockingRead read) {
    try {
      return read.read();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("조회 대기 중 인터럽트", e);
    }
  }

  /** 잠그며 읽는 조회를 대신하는 최소 인터페이스. JDK 동적 프록시라 인터페이스가 필요하다. */
  private interface LockingRead {

    String read() throws InterruptedException;
  }
}
