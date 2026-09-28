package com.sunm2n.pay.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트 간 DB 정리.
 *
 * <p>컨테이너와 Spring 컨텍스트를 모든 테스트 클래스가 공유하므로 Flyway 는 한 번만 실행되고 DB 는 테스트 간에 오염된다. 격리는 매 테스트 전 TRUNCATE
 * + 시드 재삽입이 담당한다. {@code @Transactional} 롤백은 SCENARIO 96행 규약상 쓰지 않는다 — 동시성 테스트가 작업 스레드별 독립 트랜잭션을 써야
 * 하기 때문이다.
 *
 * <p>S4 에서 {@code wallet_ledger -> wallet} FK 가 생겼다. 부모 테이블 TRUNCATE 는 자식이 비어 있어도 FK 가 있으면 실패하므로 FK
 * 검사를 끄고 지운다. 순서를 맞추는 방식은 쓰지 않는다 — FK 가 늘 때마다 순서를 다시 정해야 하고, TRUNCATE 는 참조되는 테이블이면 순서와 무관하게 막힌다.
 *
 * <p>{@code FOREIGN_KEY_CHECKS} 는 <b>세션 변수</b>다. {@code JdbcTemplate} 호출은 매번 풀에서 커넥션을 받으므로 끄기와
 * TRUNCATE 가 같은 커넥션이라는 보장이 없다. 그래서 한 커넥션 안에서 끄고, 지우고, {@code finally} 에서 원래 값으로 되돌린다. HikariCP 는
 * 반환된 커넥션의 세션 변수를 되돌리지 않는다 — TRUNCATE 가 중간에 실패해 검사가 꺼진 커넥션이 풀에 남으면, 그 커넥션을 받은 테스트는 FK 락 없이 돌아 S4
 * 재현이 조용히 사라진다 ({@code docs/plan/S4.md} 3.3).
 */
public final class DatabaseCleaner {

  private static final String FLYWAY_HISTORY = "flyway_schema_history";

  private DatabaseCleaner() {}

  public static void truncateAll(JdbcTemplate jdbcTemplate) {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
            String.class);

    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (Statement statement = connection.createStatement()) {
                String original = foreignKeyChecks(statement);
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                  for (String table : tables) {
                    if (!FLYWAY_HISTORY.equals(table)) {
                      statement.execute("TRUNCATE TABLE " + table);
                    }
                  }
                } finally {
                  statement.execute("SET FOREIGN_KEY_CHECKS = " + original);
                }
              }
              return null;
            });
  }

  private static String foreignKeyChecks(Statement statement) throws SQLException {
    try (ResultSet rs = statement.executeQuery("SELECT @@SESSION.foreign_key_checks")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
