package com.sunm2n.pay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.Wallet;
import com.sunm2n.pay.wallet.domain.exception.WalletNotFoundException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 시드 지갑은 잔액 0, 원장 없음에서 시작한다. 초기 잔액은 charge API 로 넣고 CHARGE 원장을 남긴다 (SCENARIO 42행).
 *
 * <p>테스트 메서드에 {@code @Transactional} 을 붙이지 않는다 (SCENARIO 96행).
 */
class WalletServiceTest extends AbstractIntegrationTest {

  @Autowired private WalletService walletService;

  @Test
  @DisplayName("충전하면 잔액이 늘고 같은 금액의 CHARGE 원장이 남는다")
  void chargeIncreasesBalanceAndWritesLedger() {
    Wallet charged = walletService.charge(Seeds.MEMBER_ID_1, 10_000L);

    assertThat(charged.getBalance()).isEqualTo(10_000L);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(10_000L);

    List<Map<String, Object>> ledgers = ledgersOf(Seeds.MEMBER_ID_1);
    assertThat(ledgers).hasSize(1);
    assertThat(ledgers.get(0)).containsEntry("type", LedgerType.CHARGE.name());
    assertThat(((Number) ledgers.get(0).get("amount")).longValue()).isEqualTo(10_000L);

    invariants.assertAll();
  }

  @Test
  @DisplayName("여러 번 충전하면 잔액은 누적되고 원장도 그만큼 쌓인다")
  void repeatedChargesAccumulate() {
    walletService.charge(Seeds.MEMBER_ID_1, 10_000L);
    walletService.charge(Seeds.MEMBER_ID_1, 5_000L);
    walletService.charge(Seeds.MEMBER_ID_1, 1_000L);

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(16_000L);
    assertThat(ledgersOf(Seeds.MEMBER_ID_1)).hasSize(3);

    // 조회 → 잔액 변경 → 원장 INSERT 가 한 트랜잭션 안이어야 이 불변식이 유지된다.
    // 트랜잭션이 없으면 open-in-view=false 때문에 지갑이 detached 되어 더티체킹이 일어나지 않고
    // 원장만 INSERT 되어 "잔액 == 원장 합계" 가 정상 흐름에서도 깨진다.
    invariants.assertWalletBalanceMatchesLedger();
  }

  @Test
  @DisplayName("다른 지갑의 잔액에는 영향을 주지 않는다")
  void chargeDoesNotTouchOtherWallets() {
    walletService.charge(Seeds.MEMBER_ID_1, 10_000L);

    assertThat(balanceOf(Seeds.MEMBER_ID_2)).isZero();
    assertThat(ledgersOf(Seeds.MEMBER_ID_2)).isEmpty();
  }

  @Test
  @DisplayName("존재하지 않는 지갑을 충전하면 WalletNotFoundException - 지갑은 시드로만 생긴다")
  void chargeOnMissingWalletFails() {
    assertThatThrownBy(() -> walletService.charge(9_999L, 10_000L))
        .isInstanceOf(WalletNotFoundException.class);

    assertThat(ledgerCount()).isZero();
  }

  @Test
  @DisplayName("조회하면 현재 잔액을 돌려준다")
  void getReturnsCurrentBalance() {
    walletService.charge(Seeds.MEMBER_ID_3, 7_000L);

    Wallet found = walletService.get(Seeds.MEMBER_ID_3);

    assertThat(found.getMemberId()).isEqualTo(Seeds.MEMBER_ID_3);
    assertThat(found.getBalance()).isEqualTo(7_000L);
  }

  @Test
  @DisplayName("존재하지 않는 지갑을 조회하면 WalletNotFoundException")
  void getOnMissingWalletFails() {
    assertThatThrownBy(() -> walletService.get(9_999L)).isInstanceOf(WalletNotFoundException.class);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private List<Map<String, Object>> ledgersOf(long memberId) {
    return jdbcTemplate.queryForList(
        "SELECT l.type, l.amount, l.payment_id FROM wallet_ledger l"
            + " JOIN wallet w ON w.id = l.wallet_id WHERE w.member_id = ? ORDER BY l.id",
        memberId);
  }

  private int ledgerCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_ledger", Integer.class);
  }
}
