package com.sunm2n.pay.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>끝나지 않은 워커를 그냥 두지 않는다.</b> {@code shutdownNow()} 는 인터럽트 요청일 뿐이고 JDBC 호출은 대개 인터럽트로 끊기지 않는다.
 * 살아남은 워커가 뒤늦게 커밋하면 다음 테스트의 TRUNCATE 와 겹쳐, 원인이 앞 테스트에 있는데 엉뚱한 테스트가 깨진다. 컨테이너와 DB 를 JVM 전체가 공유하므로 취소
 * 뒤 실제 종료까지 확인하고, 확인하지 못하면 {@link #verifyNoRunawayWorkers()} 가 이후 테스트를 진행시키지 않는다.
 */
public final class ConcurrentRunner {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  /** 취소를 요청한 뒤 워커가 실제로 끝나기를 기다리는 시간. */
  private static final Duration CANCEL_GRACE = Duration.ofSeconds(5);

  /** 취소에도 끝나지 않은 워커가 있었다면 그 사유. 한 번 설정되면 이 JVM 에서는 되돌리지 않는다. */
  private static volatile String runaway;

  /**
   * 출발했지만 종료를 확인하지 못한 실행. 작업을 <b>제출하기 전</b>에 넣고, 워커 전부의 실제 종료를 확인한 뒤에 뺀다 — 아직 본문에 들어가지 않은 작업도 여기
   * 있다. 취소 뒤에도 끝나지 않은 실행은 빼지 않는다.
   */
  private static final Set<Running<?>> unfinished = ConcurrentHashMap.newKeySet();

  private ConcurrentRunner() {}

  public static <T> Results<T> run(int workers, Callable<T> task) {
    return run(workers, TIMEOUT, task);
  }

  public static <T> Results<T> run(int workers, Duration timeout, Callable<T> task) {
    return start(workers, timeout, task).await();
  }

  /**
   * 워커를 출발시키고 기다리지 않고 돌아온다. 테스트 스레드가 워커를 붙잡아 둔 채 관측해야 할 때 쓴다 ({@link HeldRun}).
   *
   * <p>종료 확인은 {@link Running#await()} 가 한다. 상한은 출발 시점부터 잰다 — {@link #run} 과 같다.
   */
  public static <T> Running<T> start(int workers, Callable<T> task) {
    return start(workers, TIMEOUT, task);
  }

  static <T> Running<T> start(int workers, Duration timeout, Callable<T> task) {
    verifyNoRunawayWorkers();
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>(workers);
    Running<T> running = new Running<>(pool, workers, timeout, futures);
    unfinished.add(running);
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
    } catch (RuntimeException e) {
      // 제출한 워커는 start 에서 기다리는 중이라 본문에 들어가지 않았다. 끝까지 확인한 뒤에만 등록을 푼다.
      pool.shutdownNow();
      cancelAndConfirmStopped(pool, workers);
      if (pool.isTerminated()) {
        unfinished.remove(running);
      }
      throw e;
    }
    start.countDown();
    pool.shutdown();
    running.startClock();
    return running;
  }

  /**
   * 살아남은 워커가 있었는지 확인한다. 있으면 공유 DB 의 상태를 믿을 수 없으므로 진행하지 않는다.
   *
   * <p>러너가 출발할 때 호출한다. 정상 실행 중인 다른 러너는 거절 사유가 아니다 — 선행을 붙잡아 둔 채 추가 작업을 보내는 관측이 있다 ({@link
   * HeldRun.Side}). 테스트 경계에서는 {@link #verifyNoUnfinishedRuns()} 를 쓴다.
   */
  public static void verifyNoRunawayWorkers() {
    if (runaway != null) {
      throw new IllegalStateException("앞선 동시 실행의 워커가 끝나지 않아 공유 DB 를 신뢰할 수 없다: " + runaway);
    }
  }

  /**
   * 테스트 경계의 검사. runaway 와 함께 <b>종료를 확인하지 못한 실행</b>이 남았는지 본다.
   *
   * <p>매 테스트 전 정리 규약이 TRUNCATE 보다 먼저 호출한다. 앞 테스트의 워커가 뒤늦게 커밋하면 다음 테스트의 정리와 겹쳐, 원인이 앞 테스트에 있는데 다른
   * 테스트가 깨진다. 그렇게 만드는 대신 여기서 멈춘다. 이미 끝난 실행은 종료 확인을 받지 않았어도 남은 워커가 없으므로 지나간다.
   */
  public static void verifyNoUnfinishedRuns() {
    verifyNoRunawayWorkers();
    unfinished.removeIf(Running::isDone);
    if (!unfinished.isEmpty()) {
      throw new IllegalStateException(
          "앞 테스트의 동시 실행 " + unfinished.size() + "건이 끝나지 않았다. 공유 DB 를 정리하지 않는다: " + unfinished);
    }
  }

  private static void cancelAndConfirmStopped(ExecutorService pool, int workers) {
    if (pool.isTerminated()) {
      return;
    }
    // 인터럽트 "요청"일 뿐이다. 요청만 하고 넘어가면 워커가 계속 돌고 있을 수 있다.
    pool.shutdownNow();
    try {
      if (!pool.awaitTermination(CANCEL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
        runaway = "워커 " + workers + "개가 취소 요청 뒤에도 " + CANCEL_GRACE.toSeconds() + "초 안에 끝나지 않았다";
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      runaway = "워커 종료를 확인하는 중 인터럽트되었다";
    }
  }

  /**
   * 출발한 워커 묶음. {@link #await()} 가 상한 안의 종료를 기다리고, 넘기면 취소한 뒤 실제 종료까지 확인한다.
   *
   * <p>{@code await} 를 다시 부르면 처음 결과를 그대로 돌려준다. 관측 헬퍼가 정리하며 기다린 작업의 결과를 테스트가 이어서 읽는다.
   */
  public static final class Running<T> {

    private final ExecutorService pool;
    private final int workers;
    private final Duration timeout;
    private final List<Future<T>> futures;
    private long deadlineNanos;
    private Results<T> results;
    private RuntimeException failure;

    private Running(ExecutorService pool, int workers, Duration timeout, List<Future<T>> futures) {
      this.pool = pool;
      this.workers = workers;
      this.timeout = timeout;
      this.futures = futures;
    }

    private synchronized void startClock() {
      deadlineNanos = System.nanoTime() + timeout.toNanos();
    }

    @Override
    public String toString() {
      return "워커 " + workers + "개 (상한 " + timeout.toSeconds() + "초)";
    }

    /** 워커가 모두 끝났는가. 기다리지 않는다. */
    public boolean isDone() {
      return pool.isTerminated();
    }

    public synchronized Results<T> await() {
      if (results != null) {
        return results;
      }
      if (failure != null) {
        throw failure;
      }
      try {
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        if (!pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
          throw new IllegalStateException(
              "워커 " + workers + "개가 " + timeout.toSeconds() + "초 안에 끝나지 않았다");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        failure = new IllegalStateException("동시 실행 대기 중 인터럽트", e);
        throw failure;
      } catch (RuntimeException e) {
        failure = e;
        throw e;
      } finally {
        cancelAndConfirmStopped(pool, workers);
        if (pool.isTerminated()) {
          unfinished.remove(this);
        }
      }
      results = collect(futures);
      return results;
    }
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
