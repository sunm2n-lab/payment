package com.sunm2n.payment.support;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final Map<String, Gate> gates = new ConcurrentHashMap<>();

  /** 게이트를 무장한다. {@code parties} 개의 스레드가 모여야 통과한다. */
  public void arm(String name, int parties) {
    gates.put(name, new Gate(name, parties));
  }

  /** 무장된 게이트라면 다른 참가자를 기다린다. 무장 전이거나 이미 통과한 스레드면 그냥 돌아간다. */
  public void pass(String name) {
    Gate gate = gates.get(name);
    if (gate != null) {
      gate.pass();
    }
  }

  /**
   * 모든 게이트를 해제한다.
   *
   * <p>테스트가 게이트를 무장한 뒤 실행 전에 실패할 수도 있으므로, 해제는 실행 헬퍼가 아니라 {@link AbstractIntegrationTest} 의 매 테스트 전
   * 정리 규약이 담당한다. 어떻게 끝났든 다음 테스트로 새지 않는다.
   */
  public void reset() {
    gates.values().forEach(Gate::release);
    gates.clear();
  }

  private static final class Gate {

    private final String name;
    private final CyclicBarrier barrier;
    private final Set<Long> arrived = ConcurrentHashMap.newKeySet();

    private Gate(String name, int parties) {
      this.name = name;
      this.barrier = new CyclicBarrier(parties);
    }

    private void pass() {
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

    private void release() {
      barrier.reset();
    }
  }
}
