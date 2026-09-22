package com.sunm2n.payment.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 같은 작업을 N 개의 스레드에서 동시에 실행하고 결과를 모은다.
 *
 * <p>테스트 스레드의 트랜잭션은 작업 스레드로 전파되지 않으므로 워커마다 독립 트랜잭션이 시작된다 (SCENARIO 96행). 초기 데이터는 호출 전에 커밋되어 있어야 한다.
 *
 * <p><b>워커 수는 커넥션 풀 크기보다 작아야 한다.</b> 게이트에서 대기하는 워커는 이미 커넥션을 점유한 상태다. 워커 수가 풀 크기 이상이면 대기가 풀 고갈로 바뀌어,
 * 관측하려던 경쟁 대신 커넥션 획득 타임아웃을 보게 된다. {@code maximum-pool-size} 는 10 이다.
 *
 * <p>성공과 실패를 모두 모은다. 완료 기준이 "예상한 실패 응답과 예상 밖 오류를 구분했는지"(SCENARIO 114행)이므로 예외를 삼키지 않고 타입까지 남긴다.
 */
public final class ConcurrentRunner {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private ConcurrentRunner() {}

  public static <T> Results<T> run(int workers, Callable<T> task) {
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>(workers);
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
      start.countDown();
      pool.shutdown();
      if (!pool.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException(
            "워커 " + workers + "개가 " + TIMEOUT.toSeconds() + "초 안에 끝나지 않았다");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("동시 실행 대기 중 인터럽트", e);
    } finally {
      pool.shutdownNow();
    }
    return collect(futures);
  }

  private static <T> Results<T> collect(List<Future<T>> futures) {
    List<T> successes = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();
    for (Future<T> future : futures) {
      try {
        successes.add(future.get());
      } catch (ExecutionException e) {
        failures.add(e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("결과 수집 중 인터럽트", e);
      }
    }
    return new Results<>(List.copyOf(successes), List.copyOf(failures));
  }

  /** 워커별 실행 결과. */
  public record Results<T>(List<T> successes, List<Throwable> failures) {

    public int successCount() {
      return successes.size();
    }

    public int failureCount() {
      return failures.size();
    }

    /** 주어진 타입의 실패만 고른다. 예상한 실패와 예상 밖 오류를 구분할 때 쓴다. */
    public List<Throwable> failuresOf(Class<? extends Throwable> type) {
      return failures.stream().filter(type::isInstance).toList();
    }

    /** 주어진 타입이 아닌 실패. 하나라도 있으면 예상 밖 오류다. */
    public List<Throwable> failuresOtherThan(Class<? extends Throwable> type) {
      return failures.stream().filter(failure -> !type.isInstance(failure)).toList();
    }
  }
}
