package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.support.AbstractV2SchemaTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 과거 재현이 정말 과거 스키마에서 도는지 확인한다.
 *
 * <p>{@link AbstractV2SchemaTest} 의 배선이 조용히 최신 DB 로 붙으면 과거 재현은 green 인 채로 최신 스키마를 공유하게 된다. 그러면 S4 의
 * FK 가 재현을 바꿔도 알아차릴 수 없다.
 */
class V2SchemaContextTest extends AbstractV2SchemaTest {

  @Test
  @DisplayName("최신 스키마와 다른 데이터베이스에 붙는다")
  void usesSeparateDatabase() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

    assertThat(database).isEqualTo("payment_v2");
  }

  @Test
  @DisplayName("Flyway 는 V2 까지만 적용했다")
  void migratedUpToV2() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank DESC LIMIT 1",
            String.class);

    assertThat(version).isEqualTo("2");
  }

  @Test
  @DisplayName("FK 가 하나도 없다 - 원장 INSERT 의 FK 검사 락이 S1~S3 재현에 섞이지 않아야 한다")
  void noForeignKeys() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints"
                + " WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'",
            Integer.class);

    assertThat(count).isZero();
  }
}
