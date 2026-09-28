package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
import com.sunm2n.pay.idempotency.IdempotencyKeyInUseException;
import com.sunm2n.pay.payment.application.CancelFingerprint;
import com.sunm2n.pay.payment.application.CancelOutcome;
import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S5 동시성 회귀 — 같은 키가 동시에 오면 unique 중복 검사가 후속을 선행 트랜잭션 끝까지 기다리게 한다 ({@code docs/plan/S5.md} 6절 회귀
 * 7~10).
 *
 * <p>선행은 {@code KEY_CLAIMED} 에 붙잡는다. 후속의 INSERT 가 <b>실제로 대기 중인 것</b>을 {@link LockWaitProbe} 로 확인한 뒤
 * 풀어 준다. 확인 없이 풀면 "대기하던 INSERT 가 그대로 성공했다" 가 아니라 "선행이 끝난 뒤에 INSERT 했다" 를 본 것일 수 있다.
 *
 * <p>선행만 실패시키는 장치는 예외 주입이 아니라 <b>업무 실패</b>다. 선행은 같은 키로 20,000 을 취소해 {@code CANCEL_AMOUNT_EXCEEDED}
 * 로 tx1 전체가 롤백된다. 게이트에 새 기능을 더하지 않는다.
 *
 * <p>대기 상한은 테스트에서도 기본 3초다. 후속이 대기하는 동안 테스트가 폴링하고 선행을 풀어 줘야 하므로, 상한을 짧게 주면 느린 환경에서 후속이 먼저 1205 로
 * 끝난다. 회귀 9 만 상한을 넘겨 기다린다 — hold 의 상한 10초 안이다.
 *
 * <p>조율자를 직접 부른다. 응답의 HTTP 매핑은 {@link IdempotentCancelRegressionTest} 가 확인했고, 여기서는 {@link
 * CancelOutcome} 의 상태·본문이 같은지 본다.
 */
class IdempotentCancelConcurrencyTest extends AbstractIntegrationTest {

  private static final long CHARGE = 100_000L;
  private static final long AMOUNT = 10_000L;
  private static final long CANCEL = 3_000L;
  private static final long EXCEEDING = 20_000L;
  private static final String KEY = "cancel-key-c";
  private static final String REASON = "재전송";

  /** {@code payment.idempotency.claim-lock-wait-timeout-seconds} 기본값. */
  private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(3);

  @Autowired private IdempotentCancelCoordinator coordinator;
  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;
  @Autowired private PlatformTransactionManager transactionManager;

  private Long merchantId;
  private String database;

  @BeforeEach
  void setUp() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
  }

  @AfterEach
  void invariantsHold() {
    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 7 - 같은 키 동시 2건은 후속이 선행 커밋까지 기다렸다가 저장된 응답을 받는다. 취소 1건, 두 응답 동일")
  void concurrentSameKeyWaitsAndReplays() {
    String paymentKey = confirmedMoneyPayment("order-s5c-7");

    concurrencyGate.armHold(ConcurrencyGate.KEY_CLAIMED);
    CompletableFuture<ConcurrentRunner.Results<CancelOutcome>> running =
        runAsync(2, () -> cancel(paymentKey, CANCEL));
    releaseLeaderAfterWaiters(1);
    ConcurrentRunner.Results<CancelOutcome> results = running.join();

    assertThat(results.failures()).isEmpty();
    assertThat(results.successes()).hasSize(2);
    assertThat(results.successes().get(1)).isEqualTo(results.successes().get(0));
    assertThat(results.successes().get(0).body().balanceAmount()).isEqualTo(AMOUNT - CANCEL);
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(refundLedgerCount()).isEqualTo(1);
    assertThat(keyRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 8 - 선행이 키를 쥔 채 업무 실패로 롤백하면 대기하던 후속 INSERT 가 그대로 성공해 선점한다")
  void waitingClaimSucceedsAfterLeaderRollsBack() {
    String paymentKey = confirmedMoneyPayment("order-s5c-8");
    AtomicInteger turn = new AtomicInteger();

    concurrencyGate.armHold(ConcurrencyGate.KEY_CLAIMED);
    CompletableFuture<ConcurrentRunner.Results<CancelOutcome>> running =
        runAsync(
            2,
            () ->
                turn.getAndIncrement() == 0
                    ? cancel(paymentKey, EXCEEDING)
                    : afterLeaderClaimed(() -> cancel(paymentKey, CANCEL)));
    releaseLeaderAfterWaiters(1);
    ConcurrentRunner.Results<CancelOutcome> results = running.join();

    assertThat(results.failures())
        .as("선행만 실패한다")
        .singleElement()
        .isInstanceOf(CancelAmountExceededException.class);
    assertThat(results.successes())
        .as("내용이 달라도 IDEMPOTENCY_KEY_REUSED 가 아니다 - 롤백된 요청은 비교 대상이 아니다")
        .singleElement()
        .satisfies(outcome -> assertThat(outcome.body().balanceAmount()).isEqualTo(7_000L));
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("SELECT request_hash FROM idempotency_key", String.class))
        .as("키 행은 후속의 것이다")
        .isEqualTo(CancelFingerprint.hash(paymentKey, CANCEL, REASON));
  }

  @Test
  @DisplayName("회귀 9 - 선행이 대기 상한보다 오래 머물면 후속은 409 IDEMPOTENCY_KEY_IN_USE, 선행 커밋 뒤 재전송은 저장된 응답")
  void claimWaitBeyondTheLimitIsKeyInUse() {
    String paymentKey = confirmedMoneyPayment("order-s5c-9");
    AtomicInteger turn = new AtomicInteger();
    AtomicLong followerWaited = new AtomicLong();

    // 후속이 1205 를 받은 뒤 스스로 선행을 풀어 준다. 선행이 상한 내내 키를 쥐고 있다.
    concurrencyGate.armHold(ConcurrencyGate.KEY_CLAIMED);
    ConcurrentRunner.Results<CancelOutcome> results =
        ConcurrentRunner.run(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) {
                return cancel(paymentKey, CANCEL);
              }
              concurrencyGate.awaitArrival(ConcurrencyGate.KEY_CLAIMED);
              long start = System.nanoTime();
              try {
                return cancel(paymentKey, CANCEL);
              } finally {
                followerWaited.set(System.nanoTime() - start);
                concurrencyGate.release(ConcurrencyGate.KEY_CLAIMED);
              }
            });

    assertThat(results.successes()).hasSize(1);
    assertThat(results.failures())
        .singleElement()
        .isInstanceOf(IdempotencyKeyInUseException.class)
        .satisfies(e -> assertThat(MySqlLockErrors.isLockWaitTimeout(e)).isTrue());
    assertThat(Duration.ofNanos(followerWaited.get()))
        .as("짧은 상한에서 끝났다 - 기본 50초가 아니다")
        .isGreaterThanOrEqualTo(CLAIM_TIMEOUT)
        .isLessThan(Duration.ofSeconds(10));

    CancelOutcome resent = cancel(paymentKey, CANCEL);
    assertThat(resent).isEqualTo(results.successes().get(0));
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    assertThat(keyRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 9 - payment 잠금 대기에는 짧은 상한이 적용되지 않는다 - 상한보다 오래 기다려도 취소가 성공한다")
  void paymentLockWaitIsNotShortened() {
    String paymentKey = confirmedMoneyPayment("order-s5c-9b");
    Duration hold = CLAIM_TIMEOUT.plusSeconds(1);
    AtomicInteger turn = new AtomicInteger();
    CountDownLatch locked = new CountDownLatch(1);
    AtomicLong cancelWaited = new AtomicLong();

    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) {
                holdPaymentLock(paymentKey, locked, hold);
                return "holder";
              }
              assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
              long start = System.nanoTime();
              CancelOutcome outcome = cancel(paymentKey, CANCEL);
              cancelWaited.set(System.nanoTime() - start);
              return outcome;
            });

    assertThat(results.failures()).isEmpty();
    assertThat(Duration.ofNanos(cancelWaited.get()))
        .as("선점 뒤 상한이 복원되어 payment FOR UPDATE 는 기본 상한(50초)으로 기다린다")
        .isGreaterThan(CLAIM_TIMEOUT);
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 10 - 선행 + 대기자 2명에서 선행이 롤백해도 취소는 1건이고, 성공 응답끼리 같고, 실패는 1213 뿐이다")
  void twoWaitersAfterLeaderRollback() {
    String paymentKey = confirmedMoneyPayment("order-s5c-10");

    ConcurrentRunner.Results<CancelOutcome> results = leaderRollsBackWithTwoWaiters(paymentKey);

    // 항상 성립하는 것만 단언한다. 1213 발생 여부는 관측 테스트가 따로 본다 (6.2).
    List<Throwable> leader = results.failuresOf(CancelAmountExceededException.class);
    List<Throwable> others = results.failuresOtherThan(CancelAmountExceededException.class);
    assertThat(leader).hasSize(1);
    assertThat(others).allSatisfy(e -> assertThat(MySqlLockErrors.isDeadlock(e)).isTrue());
    assertThat(results.successes()).isNotEmpty();
    assertThat(results.successes())
        .allSatisfy(o -> assertThat(o).isEqualTo(results.successes().get(0)));
    assertThat(results.successCount() + others.size()).isEqualTo(2);
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    System.out.printf("[S5 회귀 10] 성공 %d, 1213 %d%n", results.successCount(), others.size());

    CancelOutcome resent = cancel(paymentKey, CANCEL);
    assertThat(resent).as("이후 같은 키 재전송은 저장된 응답").isEqualTo(results.successes().get(0));
    assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
  }

  /** 회귀 10 의 배치. {@link UniqueKeyWaiterDeadlockObservationTest} 가 같은 배치를 5회 돌린다. */
  private ConcurrentRunner.Results<CancelOutcome> leaderRollsBackWithTwoWaiters(String paymentKey) {
    AtomicInteger turn = new AtomicInteger();
    concurrencyGate.armHold(ConcurrencyGate.KEY_CLAIMED);
    CompletableFuture<ConcurrentRunner.Results<CancelOutcome>> running =
        runAsync(
            3,
            () ->
                turn.getAndIncrement() == 0
                    ? cancel(paymentKey, EXCEEDING)
                    : afterLeaderClaimed(() -> cancel(paymentKey, CANCEL)));
    releaseLeaderAfterWaiters(2);
    return running.join();
  }

  private CancelOutcome cancel(String paymentKey, long amount) {
    return coordinator.cancel(merchantId, KEY, paymentKey, amount, REASON);
  }

  /** 선행이 키를 쥔 뒤에 출발한다. 후속이 선행보다 먼저 선점하는 일이 없다. */
  private <T> T afterLeaderClaimed(Callable<T> follower) throws Exception {
    concurrencyGate.awaitArrival(ConcurrencyGate.KEY_CLAIMED);
    return follower.call();
  }

  /** 선행이 붙잡히고, 후속 {@code waiters} 명이 실제로 키 락을 기다리는 것을 확인한 뒤 선행을 풀어 준다. */
  private void releaseLeaderAfterWaiters(int waiters) {
    concurrencyGate.awaitArrival(ConcurrencyGate.KEY_CLAIMED);
    try {
      LockWaitProbe.awaitWaiters(database, "idempotency_key", waiters);
    } finally {
      concurrencyGate.release(ConcurrencyGate.KEY_CLAIMED);
    }
  }

  /** 워커를 다른 스레드에서 돌린다. 테스트 스레드는 그동안 대기를 확인하고 선행을 풀어 줘야 한다. */
  private static <T> CompletableFuture<ConcurrentRunner.Results<T>> runAsync(
      int workers, Callable<T> task) {
    return CompletableFuture.supplyAsync(() -> ConcurrentRunner.run(workers, task));
  }

  /** 다른 트랜잭션이 결제 행을 잠근 채 대기자가 생긴 뒤에도 {@code hold} 만큼 더 쥐고 있다. */
  private void holdPaymentLock(String paymentKey, CountDownLatch locked, Duration hold) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbcTemplate.queryForObject(
                  "SELECT id FROM payment WHERE payment_key = ? FOR UPDATE",
                  Long.class,
                  paymentKey);
              locked.countDown();
              LockWaitProbe.awaitWaiters(database, "payment", 1);
              try {
                Thread.sleep(hold.toMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            });
  }

  private String confirmedMoneyPayment(String orderId) {
    walletService.charge(Seeds.MEMBER_ID_1, CHARGE);
    String paymentKey =
        paymentService
            .create(merchantId, orderId, AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
            .getPaymentKey();
    paymentService.confirm(merchantId, paymentKey, orderId, AMOUNT);
    return paymentKey;
  }

  private int cancelRowsOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }

  private int refundLedgerCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'REFUND'", Integer.class);
  }

  private int keyRowCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_key", Integer.class);
  }
}
