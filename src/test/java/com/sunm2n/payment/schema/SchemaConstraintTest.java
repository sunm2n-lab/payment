package com.sunm2n.payment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.payment.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "제대로 만들지 않았음"을 검증하는 테스트.
 *
 * <p>이후 시나리오의 마이그레이션이 S1/S2/S6 의 전제를 조용히 깨지 못하게 막는다. 수동 쿼리가 아니라 테스트로 두는 이유가 여기에 있다.
 *
 * <p>컨텍스트가 뜬 것 자체가 {@code spring.jpa.hibernate.ddl-auto=validate} 통과, 즉 엔티티 매핑과 V1 스키마가 일치한다는 뜻이다.
 */
class SchemaConstraintTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("FK 가 하나도 없다 - 원장 INSERT 의 FK 검사 락이 S1/S2 관찰에 섞이지 않아야 한다")
  void noForeignKeys() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints"
                + " WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'",
            Integer.class);

    assertThat(count).isZero();
  }

  @Test
  @DisplayName("payment 에는 PK 와 payment_key unique 외의 인덱스가 없다 - S6 의 인덱스 부재 실험 전제")
  void paymentHasNoSearchIndexes() {
    List<String> indexedColumns =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT column_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'payment'",
            String.class);

    assertThat(indexedColumns).containsExactlyInAnyOrder("id", "payment_key");
  }

  @Test
  @DisplayName("V1 이 만든 unique 인덱스는 payment_key / member_id / api_key 셋뿐이다")
  void onlyThreeUniqueIndexes() {
    List<String> uniqueIndexes =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND non_unique = 0 AND index_name <> 'PRIMARY'",
            String.class);

    assertThat(uniqueIndexes)
        .containsExactlyInAnyOrder(
            "uk_payment_payment_key", "uk_wallet_member_id", "uk_merchant_api_key");
  }

  @Test
  @DisplayName("settlement 에는 (merchant_id, settlement_date) unique 가 없다 - S10 의 대상")
  void settlementHasNoUniqueKey() {
    List<String> indexedColumns =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT column_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'settlement'",
            String.class);

    assertThat(indexedColumns).containsExactly("id");
  }
}
