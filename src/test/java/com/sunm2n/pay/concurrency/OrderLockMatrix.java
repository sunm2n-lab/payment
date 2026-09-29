package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.infrastructure.card.FakeCardApprovalClient;
import com.sunm2n.pay.support.BulkPaymentFixture;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.LockWaitProbe.LockCount;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S6 의 대기 행렬 — V5·V6·V7 이 <b>같은 시나리오 코드</b>를 쓴다 ({@code docs/plan/S6.md} 3.1). 스키마별 테스트 클래스는
 * 베이스(스키마)와 기대 값만 다르다.
 *
 * <p>선행은 가맹점 A 의 주문 {@code order-A-1} 을 주문 기반으로 취소하다가 잠금 읽기 반환 직후({@link
 * ConcurrencyGate#ORDER_LOCKED})에 붙잡힌다. 그 상태에서 후속을 보낸다.
 *
 * <ul>
 *   <li><b>대기</b>: 후속을 보낸 뒤 {@link LockWaitProbe} 가 {@code payment} 의 대기자를 확인하고, 그때까지 후속이 하나도 끝나지
 *       않았으며, 선행을 풀어 주면 후속이 성공한다
 *   <li><b>통과</b>: 선행이 붙잡힌 채로 후속이 끝까지 성공한다
 * </ul>
 *
 * <p><b>키 배치</b>({@link BulkPaymentFixture}): A = 50, 앞뒤 이웃 A−1 = 49·A+1 = 51, 떨어진 가맹점 B = 80. 모두
 * 결제 100건을 갖는다. 행렬 전에 B 의 READY 결제 하나를 만든다(5번). 그 뒤로 만드는 결제의 id 는 모두 10,001 이상이다.
 *
 * <p>모든 취소는 요청마다 다른 멱등키를 쓴다. 같은 키의 선점 대기(S5)가 섞이지 않고, 서로 다른 키의 INSERT 는 충돌하지 않는다. 결제수단은 CARD 라 지갑
 * 경합이 없다.
 */
final class OrderLockMatrix {

  static final long A = 50L;
  static final long A_BEFORE = A - 1;
  static final long A_AFTER = A + 1;
  static final long B = 80L;

  /** 11번의 후속 7건. 모두 A 와 다른 가맹점이다. */
  static final long[] OTHERS = {10L, 20L, 30L, 60L, 70L, 90L, 100L};

  static final String LEADER_ORDER = BulkPaymentFixture.orderIdOf(A, 1);

  static final String B_READY_ORDER = "order-" + B + "-ready";

  /**
   * Hibernate 가 {@code findByMerchantIdAndOrderIdForUpdate} 로 실제 내보내는 문장. EXPLAIN 은 이 문장 그대로에 건다.
   */
  static final String LOCKING_READ_SQL =
      "select p1_0.id,p1_0.amount,p1_0.approved_at,p1_0.balance_amount,p1_0.card_approval_no,"
          + "p1_0.created_at,p1_0.merchant_id,p1_0.method,p1_0.order_id,p1_0.payment_key,"
          + "p1_0.status,p1_0.wallet_id from payment p1_0"
          + " where p1_0.merchant_id=? and p1_0.order_id=? for update";

  private static final long CANCEL = 1_000L;
  private static final long NEW_AMOUNT = 10_000L;
  private static final Duration PASS_LIMIT = Duration.ofSeconds(10);

  enum Expect {
    WAIT,
    PASS
  }

  private final JdbcTemplate jdbcTemplate;
  private final PaymentService paymentService;
  private final IdempotentCancelCoordinator coordinator;
  private final ConcurrencyGate gate;
  private final FakeCardApprovalClient fakeCard;
  private final String database;
  private String bReadyPaymentKey;

  OrderLockMatrix(
      JdbcTemplate jdbcTemplate,
      PaymentService paymentService,
      IdempotentCancelCoordinator coordinator,
      ConcurrencyGate gate,
      FakeCardApprovalClient fakeCard) {
    this.jdbcTemplate = jdbcTemplate;
    this.paymentService = paymentService;
    this.coordinator = coordinator;
    this.gate = gate;
    this.fakeCard = fakeCard;
    this.database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
  }

  /** 대량 픽스처 + B 의 READY 결제. 매 테스트 전 정리 뒤에 부른다. */
  void prepare() {
    BulkPaymentFixture.load(jdbcTemplate);
    bReadyPaymentKey =
        paymentService
            .create(B, B_READY_ORDER, NEW_AMOUNT, PaymentMethod.CARD, null)
            .getPaymentKey();
  }

  /** 잠금 읽기의 실행 계획 — {@code type}, {@code key}. 대기 결과를 해석하기 전에 먼저 확인한다. */
  Map<String, Object> explain() {
    return jdbcTemplate.queryForMap("EXPLAIN " + LOCKING_READ_SQL, A, LEADER_ORDER);
  }

  /**
   * 선행이 붙잡힌 시점의 락 집계. 후속을 보내기 전에 {@code payment} 에 락을 가진 트랜잭션이 선행 하나뿐인지 먼저 확인한다.
   *
   * @param observe 선행을 붙잡아 둔 채 실행할 추가 관측. 선행의 트랜잭션 id 를 받는다
   */
  List<LockCount> leaderLocks(TrxObservation observe) {
    gate.armHold(ConcurrencyGate.ORDER_LOCKED);
    CompletableFuture<ConcurrentRunner.Results<Object>> leader =
        CompletableFuture.supplyAsync(() -> ConcurrentRunner.run(1, this::lead));
    try {
      gate.awaitArrival(ConcurrencyGate.ORDER_LOCKED);
      long trxId = LockWaitProbe.soleHolder(database, "payment");
      List<LockCount> locks = LockWaitProbe.locksOf(database, "payment", trxId);
      observe.at(trxId);
      return locks;
    } finally {
      gate.release(ConcurrencyGate.ORDER_LOCKED);
      assertThat(leader.join().failures()).isEmpty();
    }
  }

  /** 행 {@code no} 를 실행하고 기대와 같은지 확인한다. */
  void run(int no, Expect expect) {
    List<Callable<Object>> followers = followersOf(no);
    AtomicInteger turn = new AtomicInteger();
    AtomicBoolean released = new AtomicBoolean();
    AtomicInteger finishedWhileHeld = new AtomicInteger();
    CountDownLatch followersDone = new CountDownLatch(followers.size());

    gate.armHold(ConcurrencyGate.ORDER_LOCKED);
    CompletableFuture<ConcurrentRunner.Results<Object>> running =
        CompletableFuture.supplyAsync(
            () ->
                ConcurrentRunner.run(
                    1 + followers.size(),
                    () -> {
                      int me = turn.getAndIncrement();
                      if (me == 0) {
                        return lead();
                      }
                      gate.awaitArrival(ConcurrencyGate.ORDER_LOCKED);
                      try {
                        return followers.get(me - 1).call();
                      } finally {
                        if (!released.get()) {
                          finishedWhileHeld.incrementAndGet();
                        }
                        followersDone.countDown();
                      }
                    }));

    try {
      gate.awaitArrival(ConcurrencyGate.ORDER_LOCKED);
      if (expect == Expect.WAIT) {
        LockWaitProbe.awaitWaiters(database, "payment", followers.size());
      } else {
        awaitQuietly(followersDone);
      }
    } finally {
      released.set(true);
      gate.release(ConcurrencyGate.ORDER_LOCKED);
    }

    ConcurrentRunner.Results<Object> results = running.join();
    assertThat(results.failures()).as("행 %d - 선행과 후속이 모두 성공한다", no).isEmpty();
    assertThat(finishedWhileHeld.get())
        .as("행 %d - 선행이 붙잡힌 동안 끝난 후속 수 (%s)", no, expect)
        .isEqualTo(expect == Expect.PASS ? followers.size() : 0);
  }

  /** 모든 취소가 카드사를 한 번씩 불렀다 — Fake 취소 호출 수 == 성공한 취소 수. */
  void assertCardCancelsMatchCancelRows() {
    assertThat(fakeCard.getCancelCount())
        .isEqualTo(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_cancel", Integer.class));
  }

  private Object lead() {
    return coordinator.cancelByOrder(A, "lead-" + System.nanoTime(), LEADER_ORDER, CANCEL, null);
  }

  private List<Callable<Object>> followersOf(int no) {
    return switch (no) {
      case 1 -> List.of(() -> cancelByOrder(B, BulkPaymentFixture.orderIdOf(B, 1)));
      case 2 -> List.of(() -> cancelByOrder(A, BulkPaymentFixture.orderIdOf(A, 2)));
      case 3 -> List.of(() -> cancelByKey(B, BulkPaymentFixture.paymentKeyOf(B, 2)));
      case 4 -> List.of(() -> cancelByKey(A, BulkPaymentFixture.paymentKeyOf(A, 3)));
      case 5 ->
          List.of(() -> paymentService.confirm(B, bReadyPaymentKey, B_READY_ORDER, NEW_AMOUNT));
      case 6 -> List.of(() -> create(B));
      case 7 -> List.of(() -> create(A_BEFORE));
      case 8 -> List.of(() -> create(A_AFTER));
      case 9 -> List.of(() -> create(A));
      case 10 -> List.of(() -> paymentService.getByOrder(A, LEADER_ORDER));
      case 11 -> {
        List<Callable<Object>> others = new ArrayList<>();
        LongStream.of(OTHERS)
            .forEach(m -> others.add(() -> cancelByOrder(m, BulkPaymentFixture.orderIdOf(m, 1))));
        yield others;
      }
      default -> throw new IllegalArgumentException("행은 1~11 이다: " + no);
    };
  }

  private Object cancelByOrder(long merchantId, String orderId) {
    return coordinator.cancelByOrder(merchantId, "f-" + orderId, orderId, CANCEL, null);
  }

  private Object cancelByKey(long merchantId, String paymentKey) {
    return coordinator.cancel(merchantId, "f-" + paymentKey, paymentKey, CANCEL, null);
  }

  private Object create(long merchantId) {
    return paymentService.create(
        merchantId, "order-" + merchantId + "-new", NEW_AMOUNT, PaymentMethod.CARD, null);
  }

  /** 통과 기대 — 선행이 붙잡힌 동안 후속이 모두 끝나기를 기다린다. 상한을 넘기면 풀어 준 뒤 {@code finishedWhileHeld} 단언이 실패한다. */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(PASS_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @FunctionalInterface
  interface TrxObservation {
    void at(long trxId);
  }
}
