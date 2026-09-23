package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 테스트 간 정리 규약(TRUNCATE -> 시드 -> Fake 리셋)이 실제로 동작하는지 검증한다. */
class SeedFixtureTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("정리 훅이 가맹점 2건과 잔액 0 지갑 5건을 되돌려 놓는다")
  void seedIsReapplied() {
    Integer merchants = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant", Integer.class);
    Integer wallets = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet", Integer.class);
    Integer nonZeroBalances =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM wallet WHERE balance <> 0", Integer.class);
    Integer ledgers =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_ledger", Integer.class);

    assertThat(merchants).isEqualTo(Seeds.MERCHANT_COUNT);
    assertThat(wallets).isEqualTo(Seeds.WALLET_COUNT);
    // 지갑은 잔액 0 / 원장 없음으로 심어 "잔액 == 원장 합계" 가 시드 데이터에서도 유지되게 한다
    assertThat(nonZeroBalances).isZero();
    assertThat(ledgers).isZero();
  }

  @Test
  @DisplayName("시드 직후 공통 불변식이 모두 유지된다")
  void seedSatisfiesInvariants() {
    invariants.assertAll();
  }

  @Test
  @DisplayName("Fake 카드사 호출 카운터가 테스트마다 0 으로 리셋된다")
  void fakeCountersAreReset() {
    assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
    assertThat(fakeCardApprovalClient.getCancelCount()).isZero();

    fakeCardApprovalClient.approve("pk-test", 1000L);

    assertThat(fakeCardApprovalClient.getApproveCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("앞 테스트가 올린 카운터가 다음 테스트로 새지 않는다")
  void fakeCountersDoNotLeak() {
    assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
  }
}
