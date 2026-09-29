package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.Seeds;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S6 회귀 6 — 같은 결제를 {@code paymentKey} 취소와 주문 기반 취소로 동시에 (7,000 + 7,000). 한 건만 성공하고 잔여는 3,000 이다
 * ({@code docs/plan/S6.md} 6절).
 *
 * <p>두 경로는 결제를 찾는 방법이 다르지만 결국 같은 PK 레코드를 X 로 잠근다. S3 의 금액 상한이 두 경로에서 함께 지켜지는지를 본다. 순서를 양쪽으로 고정하고,
 * 후속이 <b>실제로 payment 락을 기다리는 것</b>을 {@link LockWaitProbe} 로 확인한 뒤 선행을 끝낸다.
 */
class OrderCancelConcurrencyTest extends AbstractIntegrationTest {

  private static final long AMOUNT = 10_000L;
  private static final long CANCEL = 7_000L;
  private static final String ORDER_ID = "order-s6c-6";

  @Autowired private PaymentService paymentService;
  @Autowired private IdempotentCancelCoordinator coordinator;
  @Autowired private PlatformTransactionManager transactionManager;

  private Long merchantId;
  private String database;
  private String paymentKey;

  @BeforeEach
  void confirmedCardPayment() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    paymentKey =
        paymentService
            .create(merchantId, ORDER_ID, AMOUNT, PaymentMethod.CARD, null)
            .getPaymentKey();
    paymentService.confirm(merchantId, paymentKey, ORDER_ID, AMOUNT);
  }

  @AfterEach
  void invariantsHold() {
    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 6 - 주문 기반 취소가 먼저 잠그면 paymentKey 취소는 기다렸다가 금액 초과로 끝난다")
  void orderCancelFirst() {
    AtomicInteger turn = new AtomicInteger();
    concurrencyGate.armHold(ConcurrencyGate.ORDER_LOCKED);
    CompletableFuture<ConcurrentRunner.Results<Object>> running =
        CompletableFuture.supplyAsync(
            () ->
                ConcurrentRunner.run(
                    2,
                    () -> {
                      if (turn.getAndIncrement() == 0) {
                        return coordinator.cancelByOrder(merchantId, null, ORDER_ID, CANCEL, null);
                      }
                      concurrencyGate.awaitArrival(ConcurrencyGate.ORDER_LOCKED);
                      return coordinator.cancel(merchantId, null, paymentKey, CANCEL, null);
                    }));
    concurrencyGate.awaitArrival(ConcurrencyGate.ORDER_LOCKED);
    try {
      LockWaitProbe.awaitWaiters(database, "payment", 1);
    } finally {
      concurrencyGate.release(ConcurrencyGate.ORDER_LOCKED);
    }

    assertOnlyOneCancelled(running.join());
  }

  @Test
  @DisplayName("회귀 6 - paymentKey 취소가 먼저 잠그면 주문 기반 취소는 기다렸다가 금액 초과로 끝난다")
  void paymentKeyCancelFirst() throws InterruptedException {
    CountDownLatch locked = new CountDownLatch(1);
    AtomicInteger turn = new AtomicInteger();

    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) {
                // 본선 취소를 한 트랜잭션 안에서 실행하고, 후속이 기다리는 것을 확인한 뒤에 커밋한다.
                return new TransactionTemplate(transactionManager)
                    .execute(
                        status -> {
                          Object outcome =
                              coordinator.cancel(merchantId, null, paymentKey, CANCEL, null);
                          locked.countDown();
                          LockWaitProbe.awaitWaiters(database, "payment", 1);
                          return outcome;
                        });
              }
              assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
              return coordinator.cancelByOrder(merchantId, null, ORDER_ID, CANCEL, null);
            });

    assertOnlyOneCancelled(results);
  }

  private void assertOnlyOneCancelled(ConcurrentRunner.Results<Object> results) {
    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failures())
        .singleElement()
        .isInstanceOf(CancelAmountExceededException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT balance_amount FROM payment WHERE payment_key = ?", Long.class, paymentKey))
        .isEqualTo(AMOUNT - CANCEL);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_cancel", Integer.class))
        .isEqualTo(1);
    assertThat(fakeCardApprovalClient.getCancelCount()).as("성공한 취소 수와 같다").isEqualTo(1);
  }
}
