package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.concurrency.OrderLockMatrix.Expect;
import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.exception.OrderNotFoundException;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.BulkPaymentFixture;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.LockWaitProbe.LockCount;
import com.sunm2n.pay.support.LockWaitProbe.RecordLock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S6 3 단계(본선) — {@code (merchant_id, order_id)} 복합 unique 는 잠금 범위를 대상 결제 한 행으로 좁힌다 (SCENARIO S6 "개선
 * 비교", {@code docs/plan/S6.md} 3.1, 회귀 7).
 *
 * <p>SQL 과 서비스 코드는 1·2 단계와 같다. unique 등치라 옵티마이저가 {@code const} 로 읽고, 보조 인덱스 엔트리 하나와 그 행의 PRIMARY
 * 레코드만 잠근다. 행렬의 모든 후속이 선행이 붙잡힌 채로 끝난다.
 *
 * <p><b>없는 주문</b>은 잠글 레코드가 없어 그 자리의 갭을 잠근다 (4.7). 고치지 않고 관측해 둔다 — 세 스키마의 비교가 같은 SQL 이어야 하고, S7 이 같은
 * SQL 을 RC 로 다시 돌린다.
 */
class CompositeUniqueOrderLockRegressionTest extends AbstractIntegrationTest {

  private static final String INDEX = "uk_payment_merchant_order";

  @Autowired private PaymentService paymentService;
  @Autowired private IdempotentCancelCoordinator coordinator;

  private OrderLockMatrix matrix;

  @BeforeEach
  void prepare() {
    matrix =
        new OrderLockMatrix(
            jdbcTemplate, paymentService, coordinator, concurrencyGate, fakeCardApprovalClient);
    matrix.prepare();
  }

  @AfterEach
  void invariantsHold() {
    invariants.assertAll();
    matrix.assertCardCancelsMatchCancelRows();
  }

  @Test
  @DisplayName("3 단계 - 잠금 읽기의 실행 계획은 복합 unique const 다")
  void planUsesCompositeUnique() {
    assertPlan();
  }

  @Test
  @DisplayName("3 단계 - 선행은 대상 결제 한 행만 잠근다 - 보조 인덱스와 PRIMARY 를 합쳐 한 자리 수")
  void leaderLocksOneRow() {
    List<LockCount> locks = matrix.leaderLocks(trxId -> {});
    System.out.println("[S6 V7 선행 락] " + locks);

    assertThat(
            LockWaitProbe.recordLocks(locks, INDEX, "GRANTED")
                + LockWaitProbe.recordLocks(locks, "PRIMARY", "GRANTED"))
        .isBetween(1, 9);
  }

  @ParameterizedTest(name = "행 {0} - {2} ({1})")
  @CsvSource({
    "1, PASS, 가맹점 B 의 주문 기반 취소",
    "2, PASS, 가맹점 A 의 다른 주문 취소",
    "3, PASS, 가맹점 B 의 paymentKey 취소",
    "4, PASS, 가맹점 A 의 다른 결제 paymentKey 취소",
    "5, PASS, 가맹점 B 의 READY 결제 승인",
    "6, PASS, 가맹점 B 의 새 결제 생성",
    "7, PASS, 앞 이웃 A-1 의 새 결제 생성",
    "8, PASS, 뒤 이웃 A+1 의 새 결제 생성",
    "9, PASS, 가맹점 A 의 새 결제 생성",
    "10, PASS, 가맹점 A 의 주문 비잠금 조회 (대조군)",
    "11, PASS, 다른 가맹점 주문 기반 취소 7건 - 대기자 0"
  })
  @DisplayName("회귀 7 - 대기 행렬 (V7)")
  void matrix(int no, Expect expect, String description) {
    assertPlan();
    matrix.run(no, expect);
  }

  /**
   * 4.7 관측 — 없는 주문 {@code order-50-none} 을 잠그며 찾으면 unique 인덱스에서 그 값이 들어갈 자리의 다음 엔트리에 갭락이 걸린다.
   * {@code ascii_bin} 순서로 {@code order-50-none} 은 A 의 숫자 주문들 뒤, A+1 의 첫 엔트리 앞이다. 그 갭에 들어가는 A 의 새
   * 주문({@code order-50-new})은 선행이 끝날 때까지 막히고, 다른 가맹점의 생성은 막히지 않는다.
   */
  @Test
  @DisplayName("4.7 관측 - 없는 주문을 잠그며 찾으면 그 자리의 갭을 잠가, 같은 갭에 들어가는 생성이 선행 끝까지 막힌다")
  void missingOrderLocksTheGap() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    AtomicReference<List<RecordLock>> leaderLocks = new AtomicReference<>();

    concurrencyGate.armHold(ConcurrencyGate.ORDER_LOCKED);
    CompletableFuture<ConcurrentRunner.Results<Object>> leader =
        CompletableFuture.supplyAsync(
            () ->
                ConcurrentRunner.run(
                    1,
                    () ->
                        coordinator.cancelByOrder(
                            OrderLockMatrix.A, null, "order-50-none", 1_000L, null)));
    CompletableFuture<Object> inGap;
    try {
      concurrencyGate.awaitArrival(ConcurrencyGate.ORDER_LOCKED);
      long trxId = LockWaitProbe.soleHolder(database, "payment");
      leaderLocks.set(LockWaitProbe.recordLocksOf(database, "payment", trxId));
      System.out.println("[S6 V7 없는 주문 락] " + leaderLocks.get());

      // 다른 가맹점의 생성은 이 갭 밖이다. 선행이 붙잡힌 채로 끝난다.
      paymentService.create(OrderLockMatrix.B, "order-80-new", 10_000L, PaymentMethod.CARD, null);
      inGap =
          CompletableFuture.supplyAsync(
              () ->
                  paymentService.create(
                      OrderLockMatrix.A, "order-50-new", 10_000L, PaymentMethod.CARD, null));
      LockWaitProbe.awaitWaiters(database, "payment", 1);
      assertThat(inGap).as("같은 갭에 들어가는 생성은 대기 중이다").isNotDone();
    } finally {
      concurrencyGate.release(ConcurrencyGate.ORDER_LOCKED);
    }

    assertThat(leader.join().failures()).singleElement().isInstanceOf(OrderNotFoundException.class);
    assertThat(inGap.join()).as("선행이 끝나면 생성된다").isNotNull();
    assertThat(leaderLocks.get())
        .as("잠긴 것은 레코드가 아니라 A+1 첫 엔트리 앞의 갭 하나다")
        .singleElement()
        .satisfies(
            lock -> {
              assertThat(lock.index()).isEqualTo(INDEX);
              assertThat(lock.mode()).isEqualTo("X,GAP");
              // 보조 인덱스 엔트리는 PK 를 함께 담는다 - (merchant_id, order_id, id)
              assertThat(lock.data())
                  .isEqualTo(
                      OrderLockMatrix.A_AFTER
                          + ", '"
                          + BulkPaymentFixture.orderIdOf(OrderLockMatrix.A_AFTER, 1)
                          + "', "
                          + BulkPaymentFixture.paymentIdOf(OrderLockMatrix.A_AFTER, 1));
            });
  }

  /** 계획이 기대와 다르면 대기 결과를 해석하지 않는다. */
  private void assertPlan() {
    Map<String, Object> plan = matrix.explain();
    assertThat(plan.get("type")).as("EXPLAIN type").isEqualTo("const");
    assertThat(plan.get("key")).as("EXPLAIN key").isEqualTo(INDEX);
  }
}
