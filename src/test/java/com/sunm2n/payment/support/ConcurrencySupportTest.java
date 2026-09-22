package com.sunm2n.payment.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동시성 테스트 자산 자체의 검증.
 *
 * <p>동기화 장치가 의도대로 동작하지 않으면 S1~S10 의 관측 결과를 믿을 수 없다. DB 가 필요 없으므로 컨테이너 없이 돈다.
 */
class ConcurrencySupportTest {

  private static final int WORKERS = 3;

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
  @DisplayName("해제한 게이트는 다시 무장 전 상태가 된다")
  void resetDisarmsGate() {
    ConcurrencyGate gate = new ConcurrencyGate();
    gate.arm(ConcurrencyGate.PAYMENT_READ, WORKERS);
    gate.reset();

    assertThatCode(() -> gate.pass(ConcurrencyGate.PAYMENT_READ)).doesNotThrowAnyException();
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
}
