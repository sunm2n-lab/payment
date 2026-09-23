package com.sunm2n.pay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.domain.Payment;
import com.sunm2n.pay.domain.PaymentMethod;
import com.sunm2n.pay.domain.exception.InsufficientBalanceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 재시도 정책 자체의 검증. 컨테이너 없이 돈다.
 *
 * <p>동시성 테스트로는 <b>상한 초과가 결정적이지 않다.</b> {@link com.sunm2n.pay.support.ConcurrencyGate} 는 스레드당 한 번만
 * 통과를 막으므로 2차 시도부터는 워커들이 동기화 없이 흩어지고, 우연히 겹치면 충돌이 더 나고 아니면 안 난다. 참가자 수도 라운드마다 줄어(4→3→2) 고정 참가자
 * barrier 로 재무장하기 어렵다.
 *
 * <p>그래서 <b>최대 시도·상한 초과·카운터·지연</b>은 예외를 k 번 던지는 통제된 delegate 로 여기서 확인하고, 동시성 테스트({@code
 * OptimisticLockConcurrencyTest})는 "1차 시도 충돌 → 전체 롤백 → 재시도 성공" 까지만 본다 ({@code docs/plan/S2.md}
 * 5.3).
 */
class RetryPolicyTest {

  private static final int MAX_ATTEMPTS = 4;
  private static final Duration BACKOFF = Duration.ofMillis(20);
  private static final long AMOUNT = 3_000L;

  private RetryMetrics metrics;
  private List<Duration> requestedDelays;

  @BeforeEach
  void reset() {
    metrics = new RetryMetrics();
    requestedDelays = new ArrayList<>();
  }

  @Test
  @DisplayName("충돌한 만큼만 다시 시도하고, 성공하면 거기서 멈춘다")
  void retriesUntilTheAttemptSucceeds() {
    CountingConfirmer delegate = CountingConfirmer.failingTimes(2);

    Payment confirmed = retrying(delegate, MAX_ATTEMPTS).confirm(1L, "pk", "order", AMOUNT);

    assertThat(confirmed).isNotNull();
    assertThat(delegate.calls()).as("최초 호출 + 재시도 2회").isEqualTo(3);
    assertThat(metrics.conflicts()).isEqualTo(2);
    assertThat(metrics.retries()).isEqualTo(2);
    assertThat(metrics.exhausted()).isZero();
    assertThat(requestedDelays).as("재시도마다 고정 지연을 요청한다").containsExactly(BACKOFF, BACKOFF);
    assertThat(metrics.totalBackoff()).isEqualTo(BACKOFF.multipliedBy(2));
  }

  @Test
  @DisplayName("상한을 넘기면 마지막 충돌을 그대로 올려보낸다 - maxAttempts 는 최초 호출을 포함한다")
  void givesUpAfterMaxAttemptsIncludingTheFirstCall() {
    CountingConfirmer delegate = CountingConfirmer.alwaysFailing();

    assertThatThrownBy(() -> retrying(delegate, MAX_ATTEMPTS).confirm(1L, "pk", "order", AMOUNT))
        .as("새 에러 코드를 만들지 않는다. 테스트는 예외 타입으로 확인한다")
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(delegate.calls()).as("최초 호출 1 + 재시도 3 = maxAttempts").isEqualTo(MAX_ATTEMPTS);
    assertThat(metrics.conflicts()).isEqualTo(MAX_ATTEMPTS);
    assertThat(metrics.retries()).as("마지막 시도 뒤에는 기다리지 않는다").isEqualTo(MAX_ATTEMPTS - 1);
    assertThat(metrics.exhausted()).isEqualTo(1);
    assertThat(requestedDelays).hasSize(MAX_ATTEMPTS - 1);
  }

  @Test
  @DisplayName("maxAttempts 가 1 이면 재시도하지 않는다")
  void oneAttemptMeansNoRetry() {
    CountingConfirmer delegate = CountingConfirmer.alwaysFailing();

    assertThatThrownBy(() -> retrying(delegate, 1).confirm(1L, "pk", "order", AMOUNT))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(delegate.calls()).isEqualTo(1);
    assertThat(metrics.retries()).isZero();
    assertThat(metrics.exhausted()).isEqualTo(1);
    assertThat(requestedDelays).isEmpty();
  }

  @Test
  @DisplayName("잔액 부족은 재시도하지 않는다 - 업무상 거절이라 다시 해도 결과가 같다")
  void doesNotRetryBusinessRejections() {
    CountingConfirmer delegate =
        CountingConfirmer.throwing(() -> InsufficientBalanceException.notEnough(1L, AMOUNT));

    assertThatThrownBy(() -> retrying(delegate, MAX_ATTEMPTS).confirm(1L, "pk", "order", AMOUNT))
        .isInstanceOf(InsufficientBalanceException.class);

    assertThat(delegate.calls()).isEqualTo(1);
    assertThat(metrics.conflicts()).as("충돌로 세지 않는다").isZero();
    assertThat(metrics.retries()).isZero();
    assertThat(metrics.exhausted()).isZero();
  }

  private RetryingPaymentConfirmer retrying(PaymentConfirmer delegate, int maxAttempts) {
    return new RetryingPaymentConfirmer(
        delegate, maxAttempts, BACKOFF, metrics, requestedDelays::add);
  }

  /** 호출 횟수를 세고 정해진 횟수만큼 예외를 던지는 delegate. */
  private static final class CountingConfirmer implements PaymentConfirmer {

    private final AtomicInteger calls = new AtomicInteger();
    private final int failures;
    private final java.util.function.Supplier<RuntimeException> exception;

    private CountingConfirmer(
        int failures, java.util.function.Supplier<RuntimeException> exception) {
      this.failures = failures;
      this.exception = exception;
    }

    private static CountingConfirmer failingTimes(int times) {
      return new CountingConfirmer(times, RetryPolicyTest::conflict);
    }

    private static CountingConfirmer alwaysFailing() {
      return new CountingConfirmer(Integer.MAX_VALUE, RetryPolicyTest::conflict);
    }

    private static CountingConfirmer throwing(
        java.util.function.Supplier<RuntimeException> exception) {
      return new CountingConfirmer(Integer.MAX_VALUE, exception);
    }

    @Override
    public Payment confirm(Long merchantId, String paymentKey, String orderId, long amount) {
      if (calls.incrementAndGet() <= failures) {
        throw exception.get();
      }
      return new Payment(paymentKey, orderId, merchantId, 1L, PaymentMethod.MONEY, amount);
    }

    private int calls() {
      return calls.get();
    }
  }

  private static ObjectOptimisticLockingFailureException conflict() {
    return new ObjectOptimisticLockingFailureException("wallet", 1L);
  }
}
