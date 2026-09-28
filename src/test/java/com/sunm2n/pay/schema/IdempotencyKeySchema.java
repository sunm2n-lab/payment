package com.sunm2n.pay.schema;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code idempotency_key} 스키마 전제 조회. V4 컨텍스트와 최신 컨텍스트가 같은 쿼리로 서로 다른 값을 확인한다. */
final class IdempotencyKeySchema {

  private IdempotencyKeySchema() {}

  static List<String> indexNames(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT index_name FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = 'idempotency_key'",
        String.class);
  }

  static boolean nonUniqueOf(JdbcTemplate jdbcTemplate, String indexName) {
    Integer nonUnique =
        jdbcTemplate.queryForObject(
            "SELECT DISTINCT non_unique FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'idempotency_key'"
                + " AND index_name = ?",
            Integer.class,
            indexName);
    return nonUnique != null && nonUnique == 1;
  }

  static String keyCollation(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForObject(
        "SELECT collation_name FROM information_schema.columns"
            + " WHERE table_schema = DATABASE() AND table_name = 'idempotency_key'"
            + " AND column_name = 'idempotency_key'",
        String.class);
  }
}
