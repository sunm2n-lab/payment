package com.sunm2n.pay.payment.application.confirmation;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
import com.sunm2n.pay.payment.domain.Payment;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * 낙관적 충돌을 트랜잭션 <b>바깥</b>에서 재시도하는 데코레이터.
 *
 * <pre>RetryingPaymentConfirmer (트랜잭션 없음)  →  optimisticDebitConfirmer (@Transactional)</pre>
 *
 * <p>충돌은 flush/commit 에서 터지고 트랜잭션 전체가 롤백된다 (SCENARIO 97행). 전략은 confirmer 의 트랜잭션 <b>안</b>에 있으므로 재시도는
 * confirmer 를 감싸야 한다. 이 클래스에 {@code @Transactional} 을 붙이면 재시도가 이미 롤백이 예정된 같은 트랜잭션 안에서 돌아 아무 의미가 없다.
 *
 * <p><b>S1 의 트랜잭션 경계 결정이 여기서 값을 한다.</b> 롤백되면 CAS 의 {@code IN_PROGRESS} 전이도 함께 취소되므로(SCENARIO 127행),
 * 재시도는 다시 {@code READY} 를 보고 CAS 를 정상적으로 통과한다.
 *
 * <p>Spring Retry 를 쓰지 않는다. retry advice 가 transaction advice 를 감싸는지 확인하는 대신 경계가 코드에 보이게 한다.
 *
 * <p><b>무엇을 재시도할지는 배선이 정한다.</b> S2-a 는 낙관적 충돌({@link #OPTIMISTIC_CONFLICT}), S4 는 데드락({@link
 * #DEADLOCK})이다. 어느 쪽이든 트랜잭션 전체가 롤백된 실패만 대상으로 삼는다 — 새 트랜잭션에서 다시 하는 것이 의미가 있으려면 앞선 시도의 흔적이 DB 에 남아
 * 있지 않아야 한다. 잔액 부족은 이 요청의 업무상 거절이므로 어느 조건에서도 그대로 올려보낸다 - 다시 시도해도 결과가 같고, 무엇보다 실패의 의미가 다르다.
 */
public class RetryingPaymentConfirmer implements PaymentConfirmer {

  /** S2-a — 커밋 시점의 버전 충돌. */
  public static final Predicate<RuntimeException> OPTIMISTIC_CONFLICT =
      e -> e instanceof OptimisticLockingFailureException;

  /**
   * S4 — 원인 체인에 1213 이 있는 실패만. 락 대기 타임아웃(1205)은 이번 실험의 정책상 재시도하지 않는다. 상대가 락을 오래 보유하고 있다는 신호라, 재시도가
   * 성공할 수도 있지만 대기열을 늘릴 수도 있다 ({@code docs/plan/S4.md} 6절).
   */
  public static final Predicate<RuntimeException> DEADLOCK = MySqlLockErrors::isDeadlock;

  private final PaymentConfirmer delegate;
  private final Predicate<RuntimeException> retryable;
  private final int maxAttempts;
  private final Duration backoff;
  private final RetryMetrics metrics;
  private final Sleeper sleeper;

  /**
   * @param retryable 재시도할 실패. 이 조건에 맞지 않는 예외는 즉시 올려보낸다
   * @param maxAttempts <b>최초 호출을 포함</b>한 최대 시도 횟수
   * @param backoff 재시도 전 대기 시간. 고정값이다 - 지수 백오프는 관측값의 해석을 복잡하게 만든다
   */
  public RetryingPaymentConfirmer(
      PaymentConfirmer delegate,
      Predicate<RuntimeException> retryable,
      int maxAttempts,
      Duration backoff,
      RetryMetrics metrics,
      Sleeper sleeper) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts 는 최초 호출을 포함하므로 1 이상이어야 한다: " + maxAttempts);
    }
    this.delegate = delegate;
    this.retryable = retryable;
    this.maxAttempts = maxAttempts;
    this.backoff = backoff;
    this.metrics = metrics;
    this.sleeper = sleeper;
  }

  @Override
  public Payment confirm(Long merchantId, String paymentKey, String orderId, long amount) {
    for (int attempt = 1; ; attempt++) {
      try {
        return delegate.confirm(merchantId, paymentKey, orderId, amount);
      } catch (RuntimeException e) {
        if (!retryable.test(e)) {
          throw e;
        }
        metrics.recordConflict();
        if (attempt >= maxAttempts) {
          // 새 에러 코드를 만들지 않는다. 본선이 아니므로 API 응답 규약을 늘리지 않고, 마지막 충돌을 그대로 올려보낸다.
          metrics.recordExhausted();
          throw e;
        }
        sleeper.sleep(backoff);
        metrics.recordRetry(backoff);
      }
    }
  }

  /** 대기 방식. 테스트가 실제로 기다리지 않고 요청된 지연을 기록할 수 있게 뽑았다. */
  public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper real() {
      return duration -> {
        try {
          Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("재시도 대기 중 인터럽트", e);
        }
      };
    }
  }
}
