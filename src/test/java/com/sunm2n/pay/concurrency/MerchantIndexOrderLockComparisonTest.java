package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.concurrency.OrderLockMatrix.Expect;
import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.support.AbstractV6SchemaTest;
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
 * S6 2 단계 비교 — {@code merchant_id} 단일 인덱스는 잠금 범위를 가맹점 하나로 좁힌다 (SCENARIO S6 "개선 비교", {@code
 * docs/plan/S6.md} 3.1).
 *
 * <p>다른 가맹점은 더 이상 막히지 않는다. 그러나 {@code order_id} 는 인덱스에 없어 가맹점의 모든 행을 읽어 비교하고, REPEATABLE READ 는 조건에
 * 맞지 않은 행의 락도 풀지 않는다. 그래서 <b>같은 가맹점의 다른 주문·다른 결제</b>는 여전히 막힌다. 스캔 끝의 갭락 때문에 앞 이웃(A−1)과 A 자신의 새 결제
 * 생성도 막힌다 — 설명과 락 확인은 {@link MerchantIndexGapObservationTest} 에 있다.
 *
 * <p>SQL 과 서비스 코드는 1 단계({@link UnindexedOrderLockReproductionTest})와 같다. 다른 것은 스키마(V6)와 기대 값뿐이다.
 */
class MerchantIndexOrderLockComparisonTest extends AbstractV6SchemaTest {

  private static final String INDEX = "idx_payment_merchant_id";

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
  @DisplayName("2 단계 - 잠금 읽기의 실행 계획은 단일 인덱스 ref 다")
  void planUsesMerchantIndex() {
    assertPlan();
  }

  @Test
  @DisplayName("2 단계 - 선행은 그 가맹점의 엔트리와 행만 잠근다 - 보조 인덱스와 PRIMARY 가 각각 가맹점의 행 수 수준")
  void leaderLocksOneMerchant() {
    List<LockCount> locks = matrix.leaderLocks(trxId -> {});
    System.out.println("[S6 V6 선행 락] " + locks);

    int perMerchant = BulkPaymentFixture.PAYMENTS_PER_MERCHANT;
    assertThat(LockWaitProbe.recordLocks(locks, INDEX, "GRANTED"))
        .as("보조 인덱스 - A 의 엔트리 + 끝 갭 + 페이지 경계")
        .isBetween(perMerchant, perMerchant + 5);
    assertThat(LockWaitProbe.recordLocks(locks, "PRIMARY", "GRANTED"))
        .as("PRIMARY - A 의 행")
        .isEqualTo(perMerchant);
  }

  @ParameterizedTest(name = "행 {0} - {2} ({1})")
  @CsvSource({
    "1, PASS, 가맹점 B 의 주문 기반 취소",
    "2, WAIT, 가맹점 A 의 다른 주문 취소",
    "3, PASS, 가맹점 B 의 paymentKey 취소",
    "4, WAIT, 가맹점 A 의 다른 결제 paymentKey 취소",
    "5, PASS, 가맹점 B 의 READY 결제 승인",
    "6, PASS, 가맹점 B 의 새 결제 생성",
    "7, WAIT, 앞 이웃 A-1 의 새 결제 생성 - A 첫 엔트리의 next-key",
    "8, PASS, 뒤 이웃 A+1 의 새 결제 생성 - 잠긴 갭 밖",
    "9, WAIT, 가맹점 A 의 새 결제 생성 - A+1 첫 엔트리의 X,GAP",
    "10, PASS, 가맹점 A 의 주문 비잠금 조회 (대조군)",
    "11, PASS, 다른 가맹점 주문 기반 취소 7건 - 대기자 0"
  })
  @DisplayName("2 단계 - 대기 행렬 (V6)")
  void matrix(int no, Expect expect, String description) {
    assertPlan();
    matrix.run(no, expect);
  }

  /** 계획이 기대와 다르면 대기 결과를 해석하지 않는다. */
  private void assertPlan() {
    Map<String, Object> plan = matrix.explain();
    assertThat(plan.get("type")).as("EXPLAIN type").isEqualTo("ref");
    assertThat(plan.get("key")).as("EXPLAIN key").isEqualTo(INDEX);
  }
}
