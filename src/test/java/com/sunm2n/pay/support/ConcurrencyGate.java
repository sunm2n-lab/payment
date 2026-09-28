package com.sunm2n.pay.support;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 동시성 테스트의 동기화 지점.
 *
 * <p>"동시에 시작시키는 것만으로 결과를 보장하지 않는다"(SCENARIO 94행). 두 요청의 조회 완료 같은 필요한 시점을 barrier 로 맞춘다. 게이트는 이름으로
 * 구분하고, {@link #arm(String, int)} 으로 무장하기 전에는 아무 일도 하지 않으므로 나머지 테스트에는 영향이 없다.
 *
 * <p><b>스레드당 한 번만 통과시킨다.</b> 같은 요청이 같은 리포지터리 메서드를 두 번 호출할 수 있기 때문이다. 예를 들어 S1 의 조건부 UPDATE 구현은 CAS
 * 성공 후 영속성 컨텍스트가 비워진 엔티티를 재조회하는데, 그때 두 번째로 barrier 에 걸리면 나머지 참가자가 영영 오지 않아 테스트가 멈춘다.
 *
 * <p>대기에는 상한을 둔다. 기대와 달리 참가자가 모이지 않는 경우 테스트가 멈추는 대신 실패해야 한다.
 *
 * <p>지점은 두 종류로 무장할 수 있다. {@link #arm} 의 barrier 는 "동시에 출발" 만 보장한다. "A 가 읽음 → B 가 커밋 → A 가 씀" 처럼
 * <b>순서</b>가 필요하면 {@link #armHold} 의 hold 를 쓴다 (S3 재현 2).
 */
public class ConcurrencyGate {

  /** {@code PaymentRepository.findByPaymentKey} 반환 직후 — 모두 같은 상태를 읽은 시점. */
  public static final String PAYMENT_READ = "payment-read";

  /** 지갑 잔액 조회 반환 직후 — 모두 같은 잔액을 읽은 시점. */
  public static final String WALLET_READ = "wallet-read";

  /**
   * {@code WalletRepository.findByIdForUpdate} <b>호출 직전</b> — 아직 아무도 wallet 락을 잡지 않은 시점.
   *
   * <p>잠그며 읽는 조회의 <b>반환 직후</b>에는 게이트를 걸 수 없다. 먼저 X 락을 잡은 워커가 여기서 대기하는 동안 나머지는 락에 막혀 게이트에 도달하지 못하고,
   * 참가자가 모이지 않아 테스트가 영영 멈춘다.
   *
   * <p>이 게이트가 보장하는 것은 <b>참가자 누구도 아직 wallet 락을 획득하지 않았다</b>는 것뿐이다. 그 시점에 각 워커가 이미 어떤 락을 들고 있는지는 경로마다
   * 다르다 — 승인 워커는 S1 의 CAS 로 payment 의 X 락을 이미 보유한다. 또한 DB 락 <b>대기</b>가 실제로 일어났다는 증거도 아니다. 동시 출발만
   * 보장한다.
   */
  public static final String WALLET_LOCK_ATTEMPT = "wallet-lock-attempt";

  /**
   * {@code PaymentRepository.findByIdForUpdate} <b>호출 직전</b> — 아직 아무도 payment 락을 잡지 않은 시점 (S3).
   *
   * <p>비관적 락 취소는 그 앞에서 스칼라 소유 조회만 하므로, 이 시점에 참가자가 보유한 락은 없다. {@link #WALLET_LOCK_ATTEMPT} 와 같이 동시
   * 출발만 보장하고 DB 락 대기가 일어났다는 증거는 아니다.
   */
  public static final String PAYMENT_LOCK_ATTEMPT = "payment-lock-attempt";

  /**
   * {@code WalletLedgerRepository.save} <b>반환 직후</b> — 원장 INSERT 가 FK 검사로 wallet 행에 S 락을 잡은 시점
   * (S4).
   *
   * <p>락을 잡은 <b>뒤에</b> 모으는 유일한 지점이다. S 락끼리는 공존하므로 참가자가 모두 도착할 수 있다. X 락을 먼저 잡는 본선에 쓰면 두 번째 참가자가
   * 도착하지 못해 상한에 걸린다 — 실패 구현 전용이다.
   */
  public static final String LEDGER_INSERTED = "ledger-inserted";

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final Map<String, Point> gates = new ConcurrentHashMap<>();

  /** 게이트를 무장한다. {@code parties} 개의 스레드가 모여야 통과한다. */
  public void arm(String name, int parties) {
    gates.put(name, new Gate(name, parties));
  }

  /**
   * 지점을 hold 로 무장한다. <b>처음 도착한 스레드 하나만</b> 붙잡고 {@link #release} 까지 멈춰 둔다. 나머지 스레드는 그냥 지나간다.
   *
   * <p>상대 스레드는 {@link #awaitArrival} 로 붙잡힌 것을 확인한 뒤 자기 작업을 끝내고 {@link #release} 한다. 그래서 붙잡힌 쪽이 그
   * 지점까지 보유한 DB 락이 상대의 경로와 <b>겹치면 안 된다.</b> 겹치면 상대가 락에 막혀 release 에 도달하지 못하고 상한에 걸려 실패한다.
   */
  public void armHold(String name) {
    gates.put(name, new Hold(name));
  }

  /** hold 로 무장한 지점에 스레드가 붙잡힐 때까지 기다린다. 상한 안에 아무도 오지 않으면 실패한다. */
  public void awaitArrival(String name) {
    hold(name).awaitArrival();
  }

  /** hold 로 붙잡아 둔 스레드를 풀어 준다. */
  public void release(String name) {
    hold(name).release();
  }

  private Hold hold(String name) {
    if (gates.get(name) instanceof Hold hold) {
      return hold;
    }
    throw new IllegalStateException("'" + name + "' 은 hold 로 무장되지 않았다");
  }

  /**
   * barrier 로 무장한 지점이 풀린 시각({@link System#nanoTime()}). 아직 풀리지 않았으면 비어 있다.
   *
   * <p>마지막 참가자가 도착해 barrier 가 열리는 순간 한 번 기록한다. 이 시각 뒤의 구간은 스레드 스케줄링·SQL 실행·예외 전달을 모두 포함한다.
   */
  public OptionalLong trippedAtNanos(String name) {
    if (gates.get(name) instanceof Gate gate) {
      return gate.trippedAtNanos();
    }
    throw new IllegalStateException("'" + name + "' 은 barrier 로 무장되지 않았다");
  }

  /** 무장된 게이트라면 다른 참가자를 기다린다. 무장 전이거나 이미 통과한 스레드면 그냥 돌아간다. */
  public void pass(String name) {
    Point gate = gates.get(name);
    if (gate != null) {
      gate.pass();
    }
  }

  /**
   * 모든 게이트를 해제한다.
   *
   * <p>테스트가 게이트를 무장한 뒤 실행 전에 실패할 수도 있으므로, 해제는 실행 헬퍼가 아니라 {@link AbstractDatabaseTest} 의 매 테스트 전 정리
   * 규약이 담당한다. 어떻게 끝났든 다음 테스트로 새지 않는다.
   */
  public void reset() {
    gates.values().forEach(Point::release);
    gates.clear();
  }

  private interface Point {

    void pass();

    void release();
  }

  private static final class Gate implements Point {

    private final String name;
    private final CyclicBarrier barrier;
    private final Set<Long> arrived = ConcurrentHashMap.newKeySet();
    private final AtomicLong trippedAt = new AtomicLong();

    private Gate(String name, int parties) {
      this.name = name;
      this.barrier = new CyclicBarrier(parties, () -> trippedAt.set(System.nanoTime()));
    }

    private OptionalLong trippedAtNanos() {
      long at = trippedAt.get();
      return at == 0 ? OptionalLong.empty() : OptionalLong.of(at);
    }

    @Override
    public void pass() {
      if (!arrived.add(Thread.currentThread().getId())) {
        return;
      }
      try {
        barrier.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        throw new IllegalStateException(
            "게이트 '" + name + "' 에서 " + TIMEOUT.toSeconds() + "초 안에 참가자가 모이지 않았다", e);
      } catch (BrokenBarrierException e) {
        throw new IllegalStateException("게이트 '" + name + "' 의 다른 참가자가 먼저 실패했다", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("게이트 '" + name + "' 대기 중 인터럽트", e);
      }
    }

    @Override
    public void release() {
      barrier.reset();
    }
  }

  private static final class Hold implements Point {

    private final String name;
    private final AtomicBoolean captured = new AtomicBoolean();
    private final CountDownLatch arrival = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    private Hold(String name) {
      this.name = name;
    }

    @Override
    public void pass() {
      // 한 스레드만 붙잡는다. 같은 스레드가 다시 와도, 다른 스레드가 와도 이미 붙잡은 뒤라 그냥 지나간다.
      if (!captured.compareAndSet(false, true)) {
        return;
      }
      arrival.countDown();
      await(released, "release 되지 않았다");
    }

    private void awaitArrival() {
      await(arrival, "붙잡힌 스레드가 없다");
    }

    @Override
    public void release() {
      released.countDown();
    }

    private void await(CountDownLatch latch, String reason) {
      try {
        if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException(
              "hold '" + name + "' 가 " + TIMEOUT.toSeconds() + "초 안에 " + reason);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("hold '" + name + "' 대기 중 인터럽트", e);
      }
    }
  }
}
