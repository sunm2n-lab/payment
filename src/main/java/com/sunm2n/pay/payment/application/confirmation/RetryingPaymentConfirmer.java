package com.sunm2n.pay.payment.application.confirmation;

import com.sunm2n.pay.payment.domain.Payment;
import java.time.Duration;
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
 * <p><b>재시도 대상은 낙관적 충돌뿐이다.</b> 잔액 부족은 이 요청의 업무상 거절이므로 그대로 올려보낸다 - 다시 시도해도 결과가 같고, 무엇보다 실패의 의미가 다르다.
 */
public class RetryingPaymentConfirmer implements PaymentConfirmer {

  private final PaymentConfirmer delegate;
  private final int maxAttempts;
  private final Duration backoff;
  private final RetryMetrics metrics;
  private final Sleeper sleeper;

  /**
   * @param maxAttempts <b>최초 호출을 포함</b>한 최대 시도 횟수
   * @param backoff 재시도 전 대기 시간. 고정값이다 - 지수 백오프는 관측값의 해석을 복잡하게 만든다
   */
  public RetryingPaymentConfirmer(
      PaymentConfirmer delegate,
      int maxAttempts,
      Duration backoff,
      RetryMetrics metrics,
      Sleeper sleeper) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts 는 최초 호출을 포함하므로 1 이상이어야 한다: " + maxAttempts);
    }
    this.delegate = delegate;
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
      } catch (OptimisticLockingFailureException e) {
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
