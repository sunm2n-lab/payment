package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.support.AbstractV6SchemaTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S6 2 단계가 정말 V6 스키마에서 도는지 확인한다. {@link V5SchemaContextTest} 의 다음 단계다.
 *
 * <p>2 단계는 {@code merchant_id} 인덱스가 <b>비유일 단일 인덱스</b>라는 전제에 선다. 최신 DB(V7, 복합 unique)로 붙으면 같은 가맹점의
 * 다른 주문이 더 이상 막히지 않아 비교가 다른 이유로 깨진다.
 */
class V6SchemaContextTest extends AbstractV6SchemaTest {

  @Test
  @DisplayName("최신 스키마와 다른 데이터베이스에 붙는다")
  void usesSeparateDatabase() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

    assertThat(database).isEqualTo("payment_v6");
  }

  @Test
  @DisplayName("Flyway 는 V6 까지만 적용했다")
  void migratedUpToV6() {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank DESC LIMIT 1",
            String.class);

    assertThat(version).isEqualTo("6");
  }

  @Test
  @DisplayName("payment 에는 idx_payment_merchant_id 비유일 인덱스가 있다")
  void merchantIndexIsNotUnique() {
    assertThat(PaymentSchema.indexNames(jdbcTemplate))
        .containsExactlyInAnyOrder("PRIMARY", "uk_payment_payment_key", "idx_payment_merchant_id");
    assertThat(PaymentSchema.nonUniqueOf(jdbcTemplate, "idx_payment_merchant_id")).isTrue();
  }

  @Test
  @DisplayName("order_id 의 collation 은 아직 utf8mb4_0900_ai_ci 다")
  void orderIdStillIgnoresCase() {
    assertThat(PaymentSchema.orderIdCollation(jdbcTemplate)).isEqualTo("utf8mb4_0900_ai_ci");
  }
}
