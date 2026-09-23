package com.sunm2n.pay.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트 간 DB 정리.
 *
 * <p>컨테이너와 Spring 컨텍스트를 모든 테스트 클래스가 공유하므로 Flyway 는 한 번만 실행되고 DB 는 테스트 간에 오염된다. 격리는 매 테스트 전 TRUNCATE
 * + 시드 재삽입이 담당한다. {@code @Transactional} 롤백은 SCENARIO 96행 규약상 쓰지 않는다 — 동시성 테스트가 작업 스레드별 독립 트랜잭션을 써야
 * 하기 때문이다.
 *
 * <p>FK 가 하나도 없으므로 {@code FOREIGN_KEY_CHECKS} 를 조작할 필요가 없다. S4 에서 FK 가 생기면 이 클래스가 먼저 깨지므로, 그때 정리
 * 순서를 다시 정한다.
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

    for (String table : tables) {
      if (!FLYWAY_HISTORY.equals(table)) {
        jdbcTemplate.execute("TRUNCATE TABLE " + table);
      }
    }
  }
}
