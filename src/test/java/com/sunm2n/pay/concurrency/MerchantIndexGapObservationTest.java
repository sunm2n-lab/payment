package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.IdempotentCancelCoordinator;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.support.AbstractV6SchemaTest;
import com.sunm2n.pay.support.BulkPaymentFixture;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.LockWaitProbe.RecordLock;
import com.sunm2n.pay.support.MySqlTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S6 6.2 관측 — V6 에서 선행이 잡은 갭이 어디인가. 대기 행렬 7·8·9번(이웃 가맹점과 A 자신의 INSERT)의 기대를 <b>키 배치와 잠긴 갭</b>으로
 * 설명하고, 설명에 쓴 락이 {@code data_locks} 에 있는지 확인한다 ({@code docs/plan/S6.md} 3.1 †, 6.2).
 *
 * <p>단일 인덱스의 엔트리 순서는 {@code (merchant_id, id)} 다. 새 결제의 id 는 기존의 어느 id 보다 크다.
 *
 * <ul>
 *   <li>A 의 엔트리 전부에 next-key {@code X}. 첫 엔트리 {@code (A, 4901)} 의 next-key 가 <b>그 앞의 갭</b>을 덮는다.
 *       A−1 의 새 엔트리 {@code (A−1, 새 id)} 는 {@code (A−1, 4900)} 과 {@code (A, 4901)} 사이에 들어가므로 대기한다
 *       (7번). 단 두 엔트리가 <b>같은 페이지</b>일 때다. 페이지 경계라면 새 엔트리는 앞 페이지의 끝(supremum 앞)에 들어가 이 락과 만나지 않는다
 *   <li>A 다음의 첫 엔트리 {@code (A+1, 5001)} 에 {@code X,GAP}. A 의 새 엔트리 {@code (A, 새 id)} 는 이 갭에 들어가므로
 *       대기한다 (9번). A+1 의 새 엔트리는 A+1 의 기존 엔트리들 뒤라 이 갭 밖이다 (8번)
 *   <li>A 의 엔트리가 페이지를 넘어가면 앞 페이지의 supremum 에도 next-key 가 걸린다. A 의 범위 안쪽이라 새 엔트리의 위치와 무관하다
 * </ul>
 *
 * <p>페이지 배치는 픽스처의 INSERT 순서가 정한다. 같은 픽스처면 같은 배치다. 배치가 바뀌어 전제가 깨지면 이 테스트가 먼저 알린다.
 */
class MerchantIndexGapObservationTest extends AbstractV6SchemaTest {

  private static final String INDEX = "idx_payment_merchant_id";

  @Autowired private PaymentService paymentService;
  @Autowired private IdempotentCancelCoordinator coordinator;

  private OrderLockMatrix matrix;
  private String database;

  @BeforeEach
  void prepare() {
    matrix =
        new OrderLockMatrix(
            jdbcTemplate, paymentService, coordinator, concurrencyGate, fakeCardApprovalClient);
    matrix.prepare();
    database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
  }

  @Test
  @DisplayName("6.2 관측 - 7·8·9번 설명에 쓴 락: A 첫 엔트리의 next-key, A+1 첫 엔트리의 X,GAP, A−1 마지막 엔트리와 같은 페이지")
  void gapLocksExplainNeighborInserts() {
    long aFirst = BulkPaymentFixture.paymentIdOf(OrderLockMatrix.A, 1);
    long aLast =
        BulkPaymentFixture.paymentIdOf(OrderLockMatrix.A, BulkPaymentFixture.PAYMENTS_PER_MERCHANT);
    long afterFirst = BulkPaymentFixture.paymentIdOf(OrderLockMatrix.A_AFTER, 1);
    long beforeLast =
        BulkPaymentFixture.paymentIdOf(
            OrderLockMatrix.A_BEFORE, BulkPaymentFixture.PAYMENTS_PER_MERCHANT);
    AtomicReference<List<RecordLock>> leaderLocks = new AtomicReference<>();
    AtomicReference<Long> beforeLastPage = new AtomicReference<>();

    matrix.leaderLocks(
        trxId -> {
          leaderLocks.set(LockWaitProbe.recordLocksOf(database, "payment", trxId));
          beforeLastPage.set(pageOfEntry(OrderLockMatrix.A_BEFORE, beforeLast));
        });
    List<RecordLock> locks = leaderLocks.get();
    locks.stream()
        .filter(l -> l.index().equals(INDEX))
        .filter(
            l ->
                !l.data().startsWith(OrderLockMatrix.A + ", ")
                    || l.data().equals(entry(OrderLockMatrix.A, aFirst))
                    || l.data().equals(entry(OrderLockMatrix.A, aLast)))
        .forEach(l -> System.out.println("[S6 V6 갭 관측] " + l));
    System.out.println("[S6 V6 갭 관측] (A-1 마지막 엔트리) page=" + beforeLastPage.get());

    RecordLock first = lockOn(locks, entry(OrderLockMatrix.A, aFirst));
    RecordLock gap = lockOn(locks, entry(OrderLockMatrix.A_AFTER, afterFirst));
    RecordLock last = lockOn(locks, entry(OrderLockMatrix.A, aLast));
    assertThat(first.mode()).as("A 첫 엔트리 - 앞 갭까지 덮는 next-key").isEqualTo("X");
    assertThat(beforeLastPage.get())
        .as("A−1 마지막 엔트리와 A 첫 엔트리가 같은 페이지 - A−1 의 새 엔트리가 그 갭에 들어간다 (7번 대기)")
        .isEqualTo(first.page());
    assertThat(gap.mode()).as("A+1 첫 엔트리 - 레코드는 잠그지 않는 갭락").isEqualTo("X,GAP");
    assertThat(gap.page())
        .as("A 마지막 엔트리와 같은 페이지 - A 의 새 엔트리가 그 갭에 들어간다 (9번 대기)")
        .isEqualTo(last.page());
    assertThat(locks)
        .as("A+1 의 다른 엔트리는 잠기지 않는다 - A+1 의 새 엔트리는 잠긴 갭 밖이다 (8번 통과)")
        .noneMatch(
            l ->
                l.index().equals(INDEX)
                    && l.data().startsWith(OrderLockMatrix.A_AFTER + ", ")
                    && !l.data().equals(entry(OrderLockMatrix.A_AFTER, afterFirst)));
  }

  private static RecordLock lockOn(List<RecordLock> locks, String data) {
    return locks.stream()
        .filter(l -> l.index().equals(INDEX) && l.data().equals(data))
        .findFirst()
        .orElseThrow(() -> new AssertionError(data + " 에 락이 없다"));
  }

  private static String entry(long merchantId, long id) {
    return merchantId + ", " + id;
  }

  /**
   * 보조 인덱스 엔트리 {@code (merchantId, id)} 가 있는 페이지. 다른 트랜잭션에서 그 가맹점을 {@code FOR SHARE} 로 읽어 자기 락의
   * 페이지를 본 뒤 롤백한다. 선행이 쥔 락과는 갭락끼리라 기다리지 않는다 — A−1 스캔이 A 첫 엔트리에 거는 것은 갭락이다.
   */
  private long pageOfEntry(long merchantId, long id) {
    try (Connection root =
        DriverManager.getConnection(
            MySqlTestContainer.MYSQL.getJdbcUrl(),
            "root",
            MySqlTestContainer.MYSQL.getPassword())) {
      root.setAutoCommit(false);
      try (PreparedStatement read =
              root.prepareStatement(
                  "SELECT id FROM "
                      + database
                      + ".payment FORCE INDEX ("
                      + INDEX
                      + ") WHERE merchant_id = ? FOR SHARE");
          PreparedStatement mine =
              root.prepareStatement(
                  "SELECT ENGINE_LOCK_ID FROM performance_schema.data_locks"
                      + " WHERE THREAD_ID = PS_CURRENT_THREAD_ID() AND INDEX_NAME = ? AND LOCK_DATA = ?")) {
        read.setLong(1, merchantId);
        read.executeQuery().close();
        mine.setString(1, INDEX);
        mine.setString(2, entry(merchantId, id));
        try (ResultSet rs = mine.executeQuery()) {
          assertThat(rs.next()).as(entry(merchantId, id) + " 의 락").isTrue();
          return LockWaitProbe.pageOf(rs.getString(1));
        }
      } finally {
        root.rollback();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
