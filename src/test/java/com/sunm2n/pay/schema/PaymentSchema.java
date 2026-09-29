package com.sunm2n.pay.schema;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code payment} 스키마 전제 조회. V5·V6·최신 컨텍스트가 같은 쿼리로 서로 다른 값을 확인한다 (S6). */
final class PaymentSchema {

  private PaymentSchema() {}

  static List<String> indexedColumns(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT column_name FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = 'payment'",
        String.class);
  }

  static List<String> indexNames(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForList(
        "SELECT DISTINCT index_name FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = 'payment'",
        String.class);
  }

  static boolean nonUniqueOf(JdbcTemplate jdbcTemplate, String indexName) {
    Integer nonUnique =
        jdbcTemplate.queryForObject(
            "SELECT DISTINCT non_unique FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'payment'"
                + " AND index_name = ?",
            Integer.class,
            indexName);
    return nonUnique != null && nonUnique == 1;
  }

  static String orderIdCollation(JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForObject(
        "SELECT collation_name FROM information_schema.columns"
            + " WHERE table_schema = DATABASE() AND table_name = 'payment'"
            + " AND column_name = 'order_id'",
        String.class);
  }
}
