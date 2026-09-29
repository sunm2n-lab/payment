package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.support.AbstractV5SchemaTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S6 0·0'·1 단계가 정말 V5 스키마에서 도는지 확인한다. {@link V2SchemaContextTest} 와 같은 역할이다.
 *
 * <p>인덱스 없는 잠금 읽기는 {@code payment} 에 검색 인덱스가 <b>없다</b>는 전제에 선다. 배선이 조용히 최신 DB 로 붙으면 실행 계획이 바뀌고, 재현이
 * 다른 이유로 깨진다. 최신 쪽 {@code SchemaConstraintTest} 가 지키던 "payment 검색 인덱스 없음" 전제를 V6 부터 이 테스트가 이어받는다.
 */
class V5SchemaContextTest extends AbstractV5SchemaTest {

  @Test
  @DisplayName("최신 스키마와 다른 데이터베이스에 붙는다")
  void usesSeparateDatabase() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

    assertThat(database).isEqualTo("payment_v5");
  }

  @Test
  @DisplayName("Flyway 는 V5 까지만 적용했다")
  void migratedUpToV5() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank DESC LIMIT 1",
            String.class);

    assertThat(version).isEqualTo("5");
  }

  @Test
  @DisplayName("payment 의 인덱스 컬럼은 id 와 payment_key 뿐이다 - S6 인덱스 부재 실험의 전제")
  void paymentHasNoSearchIndexes() {
    assertThat(PaymentSchema.indexedColumns(jdbcTemplate))
        .containsExactlyInAnyOrder("id", "payment_key");
  }

  @Test
  @DisplayName("order_id 의 collation 은 utf8mb4_0900_ai_ci 다 - 대소문자를 무시한다 (0' 단계)")
  void orderIdIgnoresCase() {
    assertThat(PaymentSchema.orderIdCollation(jdbcTemplate)).isEqualTo("utf8mb4_0900_ai_ci");
  }
}
