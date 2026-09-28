package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 정리 규약이 세션 변수를 풀에 남기지 않는지 확인한다.
 *
 * <p>풀에서는 어느 커넥션을 다시 받을지 정할 수 없으므로, 커넥션 하나만 내주는 DataSource 로 같은 세션을 다시 조회한다.
 *
 * <p>복원이 중요한 것은 <b>TRUNCATE 가 중간에 실패할 때</b>다. 실제 DB 에서 TRUNCATE 를 실패시키려면 다른 세션이 메타데이터 락을 쥐고 {@code
 * lock_wait_timeout} 을 줄여야 해서 느리고 복원할 세션 변수가 또 생긴다. 그래서 실제 커넥션을 감싸 특정 테이블의 TRUNCATE 에서만 예외를 던진다. 검증
 * 대상은 DB 의 동작이 아니라 정리 코드의 {@code finally} 다.
 */
class DatabaseCleanerTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("TRUNCATE 뒤 같은 세션의 FOREIGN_KEY_CHECKS 가 원래 값으로 돌아온다")
  void restoresForeignKeyChecks() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      JdbcTemplate sameSession = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

      DatabaseCleaner.truncateAll(sameSession);

      Integer checks =
          sameSession.queryForObject("SELECT @@SESSION.foreign_key_checks", Integer.class);
      assertThat(checks).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("TRUNCATE 가 중간에 실패해도 같은 세션의 FOREIGN_KEY_CHECKS 가 원래 값으로 돌아온다")
  void restoresForeignKeyChecksWhenTruncateFails() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      Connection failing = failingOnTruncateOf(connection, "wallet");
      JdbcTemplate sameSession = new JdbcTemplate(new SingleConnectionDataSource(failing, true));

      assertThatThrownBy(() -> DatabaseCleaner.truncateAll(sameSession))
          .as("주입한 실패가 실제로 일어났다")
          .isInstanceOf(DataAccessException.class)
          .hasRootCauseMessage("주입한 TRUNCATE 실패: wallet");

      assertThat(foreignKeyChecks(connection))
          .as("실패한 커넥션이 그대로 풀로 돌아가도 FK 검사가 꺼져 있지 않다")
          .isEqualTo(1);
    }
  }

  /** {@code TRUNCATE TABLE <table>} 만 실패시키고 나머지 호출은 실제 커넥션에 넘긴다. */
  private static Connection failingOnTruncateOf(Connection target, String table) {
    InvocationHandler connectionHandler =
        (proxy, method, args) -> {
          Object result = invoke(target, method, args);
          if (result instanceof Statement statement && "createStatement".equals(method.getName())) {
            return failingStatement(statement, "TRUNCATE TABLE " + table, table);
          }
          return result;
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            connectionHandler);
  }

  private static Statement failingStatement(Statement target, String failingSql, String table) {
    InvocationHandler statementHandler =
        (proxy, method, args) -> {
          if ("execute".equals(method.getName())
              && args != null
              && args.length == 1
              && failingSql.equals(args[0])) {
            throw new SQLException("주입한 TRUNCATE 실패: " + table);
          }
          return invoke(target, method, args);
        };
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, statementHandler);
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getTargetException();
    }
  }

  private static int foreignKeyChecks(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT @@SESSION.foreign_key_checks")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
