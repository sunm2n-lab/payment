package com.sunm2n.pay.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 재시도 관측값. SCENARIO 143행의 "재시도 횟수·지연·상한 초과를 측정"이 요구하는 것이다.
 *
 * <p>정합성 단언과 달리 이 값들에는 임계값을 걸지 않는다. CI 에서 성능 수치를 단언하면 flaky 해지기 때문이다. 다만 "1차 시도에서 충돌이 실제로 일어났는가" 같은
 * <b>결정적으로 보장되는</b> 하한은 단언에 쓴다 ({@code docs/plan/S2.md} 5.3).
 *
 * <p>여러 워커가 동시에 기록하므로 원자적 카운터를 쓴다. 회차마다 {@link #reset()} 으로 초기화한다.
 */
public class RetryMetrics {

  private final AtomicLong conflicts = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();
  private final AtomicLong exhausted = new AtomicLong();
  private final AtomicLong backoffNanos = new AtomicLong();

  /** 낙관적 충돌로 트랜잭션이 롤백된 횟수. */
  public long conflicts() {
    return conflicts.get();
  }

  /** 실제로 다시 시도한 횟수. 상한에 걸려 포기한 시도는 여기 포함되지 않는다. */
  public long retries() {
    return retries.get();
  }

  /** 최대 시도 횟수를 넘겨 포기한 요청 수. */
  public long exhausted() {
    return exhausted.get();
  }

  /**
   * 재시도 전에 기다리기로 <b>한</b> 시간의 합. 설정된 백오프를 더한 값이며 실제 대기를 계측한 것이 아니다.
   *
   * <p>여러 워커의 백오프가 <b>병렬로 겹치므로</b> 이 값을 경과 시간의 구성 요소로 읽으면 안 된다. 백오프가 차지하는 몫은 백오프를 0 으로 바꾼 같은 조건과
   * 비교해서 본다.
   */
  public Duration totalBackoff() {
    return Duration.ofNanos(backoffNanos.get());
  }

  void recordConflict() {
    conflicts.incrementAndGet();
  }

  void recordRetry(Duration backoff) {
    retries.incrementAndGet();
    backoffNanos.addAndGet(backoff.toNanos());
  }

  void recordExhausted() {
    exhausted.incrementAndGet();
  }

  public void reset() {
    conflicts.set(0);
    retries.set(0);
    exhausted.set(0);
    backoffNanos.set(0);
  }
}
