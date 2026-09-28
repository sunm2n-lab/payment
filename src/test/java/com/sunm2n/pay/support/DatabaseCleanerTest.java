package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 정리 규약이 세션 변수를 풀에 남기지 않는지 확인한다.
 *
 * <p>풀에서는 어느 커넥션을 다시 받을지 정할 수 없으므로, 커넥션 하나만 내주는 DataSource 로 같은 세션을 다시 조회한다.
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
}
