package com.sunm2n.pay.support;

import com.sunm2n.pay.support.ConcurrentRunner.Results;
import com.sunm2n.pay.support.ConcurrentRunner.Running;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 선행을 hold 로 붙잡아 둔 채 테스트 스레드가 관측하는 실행. "무장 → 출발 → 도착 확인 → 관측 → 해제 → 종료 확인" 을 한곳에 둔다 ({@code
 * docs/plan/S7_PREP.md} 4.2).
 *
 * <p>이 순서를 테스트마다 손으로 적으면 관측이 실패할 때 {@code finally} 가 게이트만 풀고 종료 확인을 건너뛴다. 워커가 살아 있는 채 테스트가 끝나고, 다음
 * 테스트의 TRUNCATE 와 겹쳐 엉뚱한 테스트가 깨진다. 그래서 여기서는:
 *
 * <ul>
 *   <li>관측이 어떻게 끝나든 게이트를 풀고, 출발한 작업 <b>전부</b>의 종료를 확인한다. 한 작업의 확인이 실패해도 나머지를 건너뛰지 않는다
 *   <li>관측이 실패했으면 <b>그 실패</b>를 다시 던진다. 해제·종료 확인의 예외와 워커의 실패는 suppressed 로 붙인다
 *   <li>관측과 종료 확인이 성공했을 때만 결과를 돌려준다. 워커의 업무 실패는 {@link Results#failures()} 에 있다. 여기서는 결과를 단언하지 않는다
 * </ul>
 *
 * <p>상한은 러너의 것(출발부터 30초, 취소 뒤 5초)을 그대로 쓴다. 취소 뒤에도 끝나지 않으면 러너가 runaway 를 기록하고 이후 DB 작업을 막는다. 상한은 강제
 * 종료의 보장이 아니다.
 */
public final class HeldRun {

  private HeldRun() {}

  /**
   * @param hold 붙잡을 지점. 처음 도착한 워커 하나만 붙잡힌다
   * @param observation 선행이 붙잡힌 뒤 테스트 스레드에서 실행한다. 끝나면 선행을 풀어 준다
   */
  public static <T> Results<T> run(
      ConcurrencyGate gate, String hold, int workers, Callable<T> task, Observation observation) {
    return run(gate, hold, workers, null, task, observation);
  }

  /** 상한을 바꾼다. 상한에 걸리는 경로를 검사하는 지원 코드 테스트용이다. {@code null} 이면 러너의 기본값이다. */
  static <T> Results<T> run(
      ConcurrencyGate gate,
      String hold,
      int workers,
      Duration timeout,
      Callable<T> task,
      Observation observation) {
    gate.armHold(hold);
    Running<T> main = start(workers, timeout, task);
    Side side = new Side(timeout);

    Throwable failure = null;
    boolean observed = false;
    try {
      gate.awaitArrival(hold);
      observation.observe(side);
      observed = true;
    } catch (Throwable t) {
      failure = t;
    }

    try {
      gate.release(hold);
    } catch (Throwable t) {
      failure = keepFirst(failure, t);
    }
    Results<T> results = null;
    try {
      results = main.await();
    } catch (Throwable t) {
      failure = keepFirst(failure, t);
    }
    List<Results<?>> sideResults = new ArrayList<>();
    for (Running<?> extra : side.started) {
      try {
        sideResults.add(extra.await());
      } catch (Throwable t) {
        failure = keepFirst(failure, t);
      }
    }

    if (failure == null) {
      return results;
    }
    if (!observed) {
      // 관측이 실패했을 때 워커의 실패는 원인이 아니라 정황이다.
      if (results != null) {
        results.failures().forEach(failure::addSuppressed);
      }
      for (Results<?> r : sideResults) {
        r.failures().forEach(failure::addSuppressed);
      }
    }
    throw HeldRun.<RuntimeException>sneaky(failure);
  }

  private static <T> Running<T> start(int workers, Duration timeout, Callable<T> task) {
    return timeout == null
        ? ConcurrentRunner.start(workers, task)
        : ConcurrentRunner.start(workers, timeout, task);
  }

  private static Throwable keepFirst(Throwable first, Throwable next) {
    if (first == null) {
      return next;
    }
    if (next != first) {
      first.addSuppressed(next);
    }
    return first;
  }

  /** 단언 실패({@link AssertionError})와 검사 예외를 감싸지 않고 그대로 던진다. 최초 실패의 타입이 곧 원인이다. */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> E sneaky(Throwable t) throws E {
    throw (E) t;
  }

  /** 선행이 붙잡힌 동안 할 관측. */
  @FunctionalInterface
  public interface Observation {
    void observe(Side side) throws Exception;
  }

  /**
   * 관측 중에 따로 보내는 작업. 러너로 돌리고 헬퍼가 종료를 확인한다 — 맨 스레드로 보내면 종료 확인도 runaway 검사도 받지 않는다.
   *
   * <p>선행이 실행 중이어도 시작할 수 있다. 결과는 헬퍼가 돌아온 뒤 {@link Running#await()} 로 읽는다.
   */
  public static final class Side {

    private final Duration timeout;
    private final List<Running<?>> started = new ArrayList<>();

    private Side(Duration timeout) {
      this.timeout = timeout;
    }

    public <R> Running<R> start(Callable<R> task) {
      Running<R> running = HeldRun.start(1, timeout, task);
      started.add(running);
      return running;
    }
  }
}
