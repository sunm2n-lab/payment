package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.support.AbstractV4SchemaTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S5 1·2차 재현이 정말 V4 스키마에서 도는지 확인한다. {@link V2SchemaContextTest} 와 같은 역할이다.
 *
 * <p>1·2차 재현은 검색 인덱스가 <b>비유일</b>이라는 전제에 선다. 배선이 조용히 최신 DB(V5, unique)로 붙으면 1차의 "키 행 2건" 이 unique
 * 위반이 되고, 재현이 다른 이유로 깨진다.
 */
class V4SchemaContextTest extends AbstractV4SchemaTest {

  @Test
  @DisplayName("최신 스키마와 다른 데이터베이스에 붙는다")
  void usesSeparateDatabase() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

    assertThat(database).isEqualTo("payment_v4");
  }

  @Test
  @DisplayName("Flyway 는 V4 까지만 적용했다")
  void migratedUpToV4() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank DESC LIMIT 1",
            String.class);

    assertThat(version).isEqualTo("4");
  }

  @Test
  @DisplayName("idempotency_key 의 검색 인덱스는 비유일이다")
  void lookupIndexIsNotUnique() {
    assertThat(IdempotencyKeySchema.nonUniqueOf(jdbcTemplate, "idx_idempotency_key_lookup"))
        .isTrue();
    assertThat(IdempotencyKeySchema.indexNames(jdbcTemplate))
        .containsExactlyInAnyOrder("PRIMARY", "idx_idempotency_key_lookup");
  }

  @Test
  @DisplayName("키 컬럼은 ascii_bin 이다 - 대소문자를 구분한다")
  void keyColumnIsCaseSensitive() {
    assertThat(IdempotencyKeySchema.keyCollation(jdbcTemplate)).isEqualTo("ascii_bin");
  }
}
