package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.concurrency.OrderLockMatrix.Expect;
import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.support.AbstractV5SchemaTest;
import com.sunm2n.pay.support.BulkPaymentFixture;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.LockWaitProbe.LockCount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S6 1 단계 재현 — 인덱스 없는 조건으로 잠그며 읽으면 전혀 다른 결제끼리 서로 막힌다 (SCENARIO S6 "재현", {@code docs/plan/S6.md}
 * 3.1).
 *
 * <p>V5 에는 {@code payment} 의 검색 인덱스가 없다. 옵티마이저는 클러스터 인덱스 전체를 스캔하고, REPEATABLE READ 는 조건에 맞지 않은 행의
 * 락도 풀지 않는다. 결과는 1행인데 잠긴 것은 전부다. 비잠금 조회(10번)만 막히지 않는다.
 *
 * <p>처음부터 V5 DB 에서 돈다. V6·V7 이 들어와도 이 결과는 바뀌지 않는다.
 */
class UnindexedOrderLockReproductionTest extends AbstractV5SchemaTest {

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
  @DisplayName("1 단계 - 잠금 읽기의 실행 계획은 풀스캔이다 (type=ALL, key=NULL)")
  void planIsFullScan() {
    assertPlan();
  }

  @Test
  @DisplayName("1 단계 - 선행 하나가 PRIMARY 의 모든 레코드를 잠근다 - 결과는 1행이다")
  void leaderLocksEveryRecord() {
    List<LockCount> locks =
        matrix.leaderLocks(
            trxId -> {
              List<String> data =
                  LockWaitProbe.lockDataOf(database(), "payment", trxId, "PRIMARY", "X");
              System.out.printf(
                  "[S6 V5 선행 락] supremum %d, 레코드 %d%n",
                  data.stream().filter("supremum pseudo-record"::equals).count(),
                  data.stream().filter(d -> !"supremum pseudo-record".equals(d)).count());
            });
    System.out.println("[S6 V5 선행 락] " + locks);

    assertThat(LockWaitProbe.recordLocks(locks, "PRIMARY", "GRANTED"))
        .as("PRIMARY 레코드 락 >= 전체 행 수 %d", BulkPaymentFixture.TOTAL_PAYMENTS)
        .isGreaterThanOrEqualTo(BulkPaymentFixture.TOTAL_PAYMENTS);
  }

  @ParameterizedTest(name = "행 {0} - {2} ({1})")
  @CsvSource({
    "1, WAIT, 가맹점 B 의 주문 기반 취소",
    "2, WAIT, 가맹점 A 의 다른 주문 취소",
    "3, WAIT, 가맹점 B 의 paymentKey 취소",
    "4, WAIT, 가맹점 A 의 다른 결제 paymentKey 취소",
    "5, WAIT, 가맹점 B 의 READY 결제 승인",
    "6, WAIT, 가맹점 B 의 새 결제 생성",
    "7, WAIT, 앞 이웃 A-1 의 새 결제 생성",
    "8, WAIT, 뒤 이웃 A+1 의 새 결제 생성",
    "9, WAIT, 가맹점 A 의 새 결제 생성",
    "10, PASS, 가맹점 A 의 주문 비잠금 조회 (대조군)",
    "11, WAIT, 다른 가맹점 주문 기반 취소 7건 - 완전 직렬화"
  })
  @DisplayName("1 단계 - 대기 행렬 (V5)")
  void matrix(int no, Expect expect, String description) {
    assertPlan();
    matrix.run(no, expect);
  }

  private String database() {
    return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
  }

  /** 계획이 기대와 다르면 대기 결과를 해석하지 않는다. */
  private void assertPlan() {
    Map<String, Object> plan = matrix.explain();
    assertThat(plan.get("type")).as("EXPLAIN type").isEqualTo("ALL");
    assertThat(plan.get("key")).as("EXPLAIN key").isNull();
  }
}
