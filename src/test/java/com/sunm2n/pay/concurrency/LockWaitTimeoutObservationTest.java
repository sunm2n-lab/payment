package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.infrastructure.WalletRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S4 — 락 대기 타임아웃(1205)을 데드락(1213)과 구분해 관찰한다 (SCENARIO 169행, {@code docs/plan/S4.md} 6절).
 *
 * <p>데드락은 대기 그래프에 순환이 생기는 즉시 감지되고 피해 트랜잭션 <b>전체</b>가 롤백된다. 단순 락 대기는 {@code
 * innodb_lock_wait_timeout} 이 지나야 끝나고, 기본 설정({@code innodb_rollback_on_timeout=OFF})에서 DB 는 <b>마지막
 * 문장만</b> 롤백한다.
 *
 * <p><b>관찰 경로를 나눈다.</b> 문장 단위 롤백은 DB 의 동작이다. 예외가 {@code @Transactional} 경계 밖으로 전파된 뒤에는 Spring 이
 * 트랜잭션 전체를 롤백하므로 관찰할 수 없다. 그래서 직접 관리하는 JDBC 트랜잭션에서, 1205 를 받은 <b>같은 커넥션으로</b> 확인한다. 반대로 호출자가 받는 예외
 * 타입은 프레임워크의 변환 결과이므로 Spring 관리 트랜잭션 안의 JPA 조회로 기록한다.
 *
 * <p><b>세션 변수를 풀에 남기지 않는다.</b> 두 경로 모두 풀에서 받은 커넥션이다. HikariCP 는 반환된 커넥션의 세션 변수를 되돌리지 않으므로, 1초 설정이
 * 남으면 후속 회귀의 정상적인 락 대기가 1205 로 끝난다. {@code finally} 에서 원래 값으로 복원한다.
 */
class LockWaitTimeoutObservationTest extends AbstractIntegrationTest {

  private static final int TIMEOUT_SECONDS = 1;

  /** 1초 설정이 실제로 적용됐는지 판정하는 상한. 기본값(50초)이 적용됐다면 여기를 한참 넘긴다. */
  private static final Duration UPPER_BOUND = Duration.ofSeconds(5);

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private WalletRepository walletRepository;

  @Test
  @DisplayName("JDBC - 1205 는 대기하던 문장만 롤백하고 트랜잭션은 살아 있다. 앞 문장의 변경이 남는다")
  void lockWaitTimeoutRollsBackOnlyTheStatement() throws SQLException {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT @@GLOBAL.innodb_rollback_on_timeout", Integer.class))
        .as("전제: 기본 설정. ON 이면 트랜잭션 전체가 롤백된다")
        .isZero();
    long lockedWallet = walletIdOf(Seeds.MEMBER_ID_1);
    long otherWallet = walletIdOf(Seeds.MEMBER_ID_2);

    try (Connection holder = dataSource.getConnection();
        Connection waiter = dataSource.getConnection()) {
      String original = sessionLockWaitTimeout(waiter);
      holder.setAutoCommit(false);
      waiter.setAutoCommit(false);
      try {
        lockWallet(holder, lockedWallet);

        execute(waiter, "SET SESSION innodb_lock_wait_timeout = " + TIMEOUT_SECONDS);
        execute(waiter, "UPDATE wallet SET balance = balance + 1 WHERE id = " + otherWallet);

        long start = System.nanoTime();
        SQLException timeout =
            catchThrowableOfType(SQLException.class, () -> lockWallet(waiter, lockedWallet));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        System.out.printf("[S4 1205/JDBC] 잠금 문장 발행 → 1205 수신: %d ms%n", waited.toMillis());

        assertThat((Throwable) timeout).as("1205 로 끝났다").isNotNull();
        assertThat(timeout.getErrorCode()).isEqualTo(MySqlLockErrors.LOCK_WAIT_TIMEOUT);
        assertThat(waited)
            .as("세션 설정(1초)대로 기다렸다")
            .isGreaterThanOrEqualTo(Duration.ofSeconds(TIMEOUT_SECONDS))
            .isLessThan(UPPER_BOUND);

        assertThat(balanceOf(waiter, otherWallet))
            .as("같은 트랜잭션의 앞 문장(+1)은 롤백되지 않았다 - 트랜잭션이 열린 채 돌아왔다")
            .isEqualTo(1L);
      } finally {
        holder.rollback();
        waiter.rollback();
        execute(waiter, "SET SESSION innodb_lock_wait_timeout = " + original);
        holder.setAutoCommit(true);
        waiter.setAutoCommit(true);
      }
    }

    assertThat(balanceOf(otherWallet)).as("정리 후 롤백으로 원래 값").isZero();
  }

  @Test
  @DisplayName("JPA - 1205 가 호출자에게 어떤 예외로 올라오는지 기록한다. 데드락과 에러 코드로 구분된다")
  void lockWaitTimeoutAsSeenThroughJpa() throws SQLException {
    long lockedWallet = walletIdOf(Seeds.MEMBER_ID_1);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try {
        lockWallet(holder, lockedWallet);

        long start = System.nanoTime();
        Throwable thrown =
            catchThrowable(
                () ->
                    transaction.executeWithoutResult(
                        status -> {
                          // JpaTransactionManager 가 JDBC 커넥션을 노출하므로 JdbcTemplate 과 JPA 조회가 같은 세션이다.
                          String original =
                              jdbcTemplate.queryForObject(
                                  "SELECT @@SESSION.innodb_lock_wait_timeout", String.class);
                          jdbcTemplate.execute(
                              "SET SESSION innodb_lock_wait_timeout = " + TIMEOUT_SECONDS);
                          try {
                            walletRepository.findByIdForUpdate(lockedWallet);
                          } finally {
                            jdbcTemplate.execute(
                                "SET SESSION innodb_lock_wait_timeout = " + original);
                          }
                        }));
        Duration waited = Duration.ofNanos(System.nanoTime() - start);
        System.out.printf(
            "[S4 1205/JPA] %s (%d ms)%n", thrown.getClass().getName(), waited.toMillis());

        assertThat(waited).as("세션 설정이 JPA 조회의 커넥션에 적용됐다").isLessThan(UPPER_BOUND);
        assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(MySqlLockErrors.isLockWaitTimeout(thrown)).isTrue();
        assertThat(MySqlLockErrors.isDeadlock(thrown)).as("재시도 조건(1213)에 걸리지 않는다").isFalse();
      } finally {
        holder.rollback();
        holder.setAutoCommit(true);
      }
    }
  }

  @Test
  @DisplayName("JdbcTemplate - 같은 1205 도 번역 경로가 다르면 다른 타입이 된다. 그래서 타입이 아니라 에러 코드로 판정한다")
  void lockWaitTimeoutAsSeenThroughJdbcTemplate() throws SQLException {
    long lockedWallet = walletIdOf(Seeds.MEMBER_ID_1);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try {
        lockWallet(holder, lockedWallet);

        Throwable thrown =
            catchThrowable(
                () ->
                    transaction.executeWithoutResult(
                        status -> {
                          String original =
                              jdbcTemplate.queryForObject(
                                  "SELECT @@SESSION.innodb_lock_wait_timeout", String.class);
                          jdbcTemplate.execute(
                              "SET SESSION innodb_lock_wait_timeout = " + TIMEOUT_SECONDS);
                          try {
                            jdbcTemplate.queryForObject(
                                "SELECT id FROM wallet WHERE id = ? FOR UPDATE",
                                Long.class,
                                lockedWallet);
                          } finally {
                            jdbcTemplate.execute(
                                "SET SESSION innodb_lock_wait_timeout = " + original);
                          }
                        }));
        System.out.printf("[S4 1205/JdbcTemplate] %s%n", thrown.getClass().getName());

        assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(MySqlLockErrors.isLockWaitTimeout(thrown)).isTrue();
        assertThat(MySqlLockErrors.isDeadlock(thrown)).isFalse();
      } finally {
        holder.rollback();
        holder.setAutoCommit(true);
      }
    }
  }

  private long walletIdOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private long balanceOf(long walletId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE id = ?", Long.class, walletId);
  }

  private static void lockWallet(Connection connection, long walletId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM wallet WHERE id = ? FOR UPDATE")) {
      statement.setLong(1, walletId);
      statement.executeQuery().close();
    }
  }

  private static long balanceOf(Connection connection, long walletId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT balance FROM wallet WHERE id = ?")) {
      statement.setLong(1, walletId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static String sessionLockWaitTimeout(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
