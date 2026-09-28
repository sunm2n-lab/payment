package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "제대로 만들지 않았음"을 검증하는 테스트.
 *
 * <p>최신 스키마 기준이다. 이후 시나리오의 마이그레이션이 S6·S10 의 전제를 조용히 깨지 못하게 막는다. S1~S3 의 "FK 0건" 전제는 과거 재현이 도는 V2 DB
 * 에서 {@link V2SchemaContextTest} 가 지킨다. 수동 쿼리가 아니라 테스트로 두는 이유가 여기에 있다.
 *
 * <p>컨텍스트가 뜬 것 자체가 {@code spring.jpa.hibernate.ddl-auto=validate} 통과, 즉 엔티티 매핑과 V1 스키마가 일치한다는 뜻이다.
 */
class SchemaConstraintTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("FK 는 wallet_ledger.wallet_id -> wallet.id 하나뿐이다 - S4 에서 처음 추가했고 다른 FK 는 없다")
  void onlyWalletLedgerForeignKey() {
    List<String> foreignKeys =
        jdbcTemplate.queryForList(
            "SELECT CONCAT(table_name, '.', column_name, ' -> ',"
                + " referenced_table_name, '.', referenced_column_name)"
                + " FROM information_schema.key_column_usage"
                + " WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL",
            String.class);

    assertThat(foreignKeys).containsExactly("wallet_ledger.wallet_id -> wallet.id");
  }

  @Test
  @DisplayName("FK 컬럼의 인덱스는 V3 가 이름 붙여 만든 것이다 - InnoDB 자동 생성 인덱스가 아니다")
  void walletLedgerIndexIsNamed() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'wallet_ledger'",
            String.class);

    assertThat(indexes).containsExactlyInAnyOrder("PRIMARY", "idx_wallet_ledger_wallet_id");
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
