package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
import com.sunm2n.pay.payment.application.CancelFingerprint;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.cancellation.LockingLookupIdempotentCanceller;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractV4SchemaTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 2차 재현 — 없는 키를 {@code FOR UPDATE} 로 찾으면 갭락이 공존하고, INSERT 끼리 데드락이 된다 (SCENARIO S5 "개선 2차"·"관찰
 * 3", 180~181행).
 *
 * <p><b>최소 실험(①)이 먼저다.</b> 멱등 테이블 하나와 JDBC 세션 둘로 원인을 좁힌다. 그 뒤 서비스(②)에 붙였을 때 payment 락이 순환의 한 고리로 끼는
 * 모양을 본다 ({@code docs/plan/S5.md} 3절, 4.7).
 *
 * <p><b>V4 컨텍스트</b>에서 돈다. 검색 인덱스가 비유일이어야 "없는 키" 조회가 갭을 잠근다. unique 인덱스의 동등 조회는 결과가 달라진다.
 *
 * <p><b>이 테스트는 데드락이 재현될 때 green 이다.</b> 락 모드({@code X,GAP} GRANTED → {@code X,GAP,INSERT_INTENTION}
 * WAITING) 와 {@code LATEST DETECTED DEADLOCK} 은 수동 관측으로 {@code docs/phase1/S5.md} 에 남긴다.
 */
class GapLockDeadlockReproductionTest extends AbstractV4SchemaTest {

  private static final long CHARGE = 100_000L;
  private static final long AMOUNT = 10_000L;
  private static final long CANCEL = 3_000L;

  private static final String SELECT_FOR_UPDATE =
      "SELECT id FROM idempotency_key"
          + " WHERE merchant_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE";
  private static final String INSERT =
      "INSERT INTO idempotency_key (merchant_id, operation, idempotency_key, request_hash,"
          + " status, created_at) VALUES (?, ?, ?, REPEAT('0', 64), 'COMPLETED', NOW(6))";

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;
  @Autowired private LockingLookupIdempotentCanceller lockingLookupCanceller;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("① 최소 실험 - 양옆 기준 행 사이의 없는 키를 두 세션이 FOR UPDATE 로 찾은 뒤 INSERT 하면 한쪽이 1213")
  void minimalGapLockDeadlock() throws Exception {
    // 기준 행. k20 을 찾으면 k30 앞의 갭 (k10, k30) 을 잠근다. 기준 행이 없으면 supremum 을 잠근다.
    insertCommitted("k10");
    insertCommitted("k30");

    try (Connection t1 = transaction();
        Connection t2 = transaction()) {
      assertThat(selectForUpdate(t1, "k20")).as("T1 은 없음을 본다 - 갭 X").isFalse();
      assertThat(selectForUpdate(t2, "k20")).as("T2 도 없음을 본다 - 갭락끼리 공존").isFalse();

      // 두 INSERT 를 동시에 보낸다. 어느 쪽이 먼저 대기에 들어가든 나중 쪽이 순환을 완성하고, InnoDB 가 즉시 감지한다.
      Queue<Connection> sessions = new ConcurrentLinkedQueue<>(List.of(t1, t2));
      ConcurrentRunner.Results<String> results =
          ConcurrentRunner.run(
              2,
              () -> {
                Connection session = sessions.poll();
                insert(session, "k20");
                session.commit();
                return "committed";
              });

      assertThat(results.successCount()).isEqualTo(1);
      assertThat(results.failures())
          .singleElement()
          .satisfies(e -> assertThat(MySqlLockErrors.isDeadlock(e)).isTrue());
    }

    assertThat(keyRowsOf("k20")).as("피해 세션의 INSERT 는 트랜잭션째 롤백됐다").isEqualTo(1);
  }

  @Test
  @DisplayName("② 서비스 적용 - 같은 키 두 요청이 모두 '없음' 을 본 뒤 진행하면 성공 1, 1213 1, 취소 1건")
  void lockingLookupDeadlocksInService() {
    String paymentKey = confirmedMoneyPayment("order-s5-2");

    concurrencyGate.arm(ConcurrencyGate.KEY_LOOKED_UP, 2);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            2,
            () ->
                lockingLookupCanceller.cancel(
                    merchantId, "cancel-s5-2", paymentKey, CANCEL, "재전송"));

    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failures())
        .as("누가 피해자인지는 단언하지 않는다")
        .singleElement()
        .satisfies(e -> assertThat(MySqlLockErrors.isDeadlock(e)).isTrue());

    assertThat(cancelRowsOf(paymentKey)).as("중복은 막혔다 - 데드락 덕분에").isEqualTo(1);
    assertThat(balanceAmountOf(paymentKey)).isEqualTo(AMOUNT - CANCEL);
    assertThat(refundLedgerCount()).isEqualTo(1);
    assertThat(keyRowsOf("cancel-s5-2")).isEqualTo(1);
    invariants.assertAll();
  }

  /** 커밋하지 않고 닫으면 HikariCP 가 롤백하고 자동 커밋을 되돌려 풀에 반환한다. */
  private Connection transaction() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    return connection;
  }

  private boolean selectForUpdate(Connection connection, String key) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_FOR_UPDATE)) {
      statement.setLong(1, merchantId);
      statement.setString(2, CancelFingerprint.OPERATION);
      statement.setString(3, key);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private void insert(Connection connection, String key) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setLong(1, merchantId);
      statement.setString(2, CancelFingerprint.OPERATION);
      statement.setString(3, key);
      statement.executeUpdate();
    }
  }

  private void insertCommitted(String key) {
    jdbcTemplate.update(INSERT, merchantId, CancelFingerprint.OPERATION, key);
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

  private long balanceAmountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT balance_amount FROM payment WHERE payment_key = ?", Long.class, paymentKey);
  }

  private int refundLedgerCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'REFUND'", Integer.class);
  }

  private int keyRowsOf(String key) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM idempotency_key WHERE idempotency_key = ?", Integer.class, key);
  }
}
