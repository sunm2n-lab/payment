package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 관측 — unique 는 데드락을 없애지 않는다 ({@code docs/plan/S5.md} 4.6, 6.2).
 *
 * <pre>
 * L   INSERT key            X,REC_NOT_GAP        ── KEY_CLAIMED hold
 * F1  INSERT key            중복 검사 S          WAITING
 * F2  INSERT key            중복 검사 S          WAITING   (LockWaitProbe 로 2명 확인 뒤 L 해제)
 * L   업무 실패 → 롤백       F1·F2 의 S 가 S,GAP 으로 남아 둘 다 GRANTED
 * F1  INSERT                insert intention → F2 의 S,GAP 에 막힘
 * F2  INSERT                insert intention → F1 의 S,GAP 에 막힘 → 순환 → 한쪽 1213
 * </pre>
 *
 * <p>회귀 10 과 같은 배치를 5회 돌려 1213 발생 여부와 피해자 수를 본다. 회귀 10 은 항상 성립하는 것만 단언하고, 이 테스트는 관측 결과를 고정한다. 수동
 * 관측(docker, root)에서도 같은 모양이었다 — 대기자 둘 모두 {@code S,GAP} GRANTED, 각자의 {@code X,GAP,INSERT_INTENTION}
 * 이 상대를 기다린다 ({@code docs/phase1/S5.md}).
 *
 * <p><b>이 테스트는 데드락이 재현될 때 green 이다.</b>
 */
class UniqueKeyWaiterDeadlockObservationTest extends AbstractIntegrationTest {

  private static final int ROUNDS = 5;
  private static final long AMOUNT = 10_000L;

  @Autowired private IdempotentCancelCoordinator coordinator;
  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Test
  @DisplayName("관측 - 선행 롤백 뒤 대기자 둘은 매번 한쪽이 1213 으로 끝나고, 남은 쪽이 선점해 취소는 1건이다")
  void twoWaitersDeadlockAfterLeaderRollback() {
    Long merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    walletService.charge(Seeds.MEMBER_ID_1, ROUNDS * AMOUNT);
    List<String> distribution = new ArrayList<>();

    for (int round = 1; round <= ROUNDS; round++) {
      String orderId = "order-s5o-" + round;
      String paymentKey =
          paymentService
              .create(merchantId, orderId, AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
              .getPaymentKey();
      paymentService.confirm(merchantId, paymentKey, orderId, AMOUNT);
      String key = "cancel-key-o" + round;
      AtomicInteger turn = new AtomicInteger();

      concurrencyGate.reset();
      concurrencyGate.armHold(ConcurrencyGate.KEY_CLAIMED);
      CompletableFuture<ConcurrentRunner.Results<CancelOutcome>> running =
          CompletableFuture.supplyAsync(
              () ->
                  ConcurrentRunner.run(
                      3,
                      () -> {
                        if (turn.getAndIncrement() == 0) {
                          return coordinator.cancel(merchantId, key, paymentKey, 20_000L, "관측");
                        }
                        concurrencyGate.awaitArrival(ConcurrencyGate.KEY_CLAIMED);
                        return coordinator.cancel(merchantId, key, paymentKey, 3_000L, "관측");
                      }));
      concurrencyGate.awaitArrival(ConcurrencyGate.KEY_CLAIMED);
      try {
        LockWaitProbe.awaitWaiters(database, "idempotency_key", 2);
      } finally {
        concurrencyGate.release(ConcurrencyGate.KEY_CLAIMED);
      }
      ConcurrentRunner.Results<CancelOutcome> results = running.join();

      long deadlocks = results.failures().stream().filter(MySqlLockErrors::isDeadlock).count();
      distribution.add(
          "round %d: 성공 %d, 1213 %d".formatted(round, results.successCount(), deadlocks));

      assertThat(results.failuresOf(CancelAmountExceededException.class)).hasSize(1);
      assertThat(results.successCount()).as("round " + round).isEqualTo(1);
      assertThat(deadlocks).as("round " + round).isEqualTo(1);
      assertThat(cancelRowsOf(paymentKey)).isEqualTo(1);
    }

    System.out.println("[S5 관측 6.2] " + distribution);
    invariants.assertAll();
  }

  private int cancelRowsOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }
}
