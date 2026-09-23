package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SCENARIO 103행의 공통 불변식 검증.
 *
 * <p>S1~S10 이 계속 재사용하는 자산이다. 실패·재시도 후에도 아래 조건이 유지되어야 한다.
 *
 * <ol>
 *   <li>지갑 잔액 == 해당 지갑 원장 금액의 합, 그리고 음수가 아님
 *   <li>{@code payment.balance_amount == amount - 취소 합계}
 *   <li>취소 합계 <= 승인 금액
 * </ol>
 *
 * <p>1번의 "음수 아님"을 DB 제약(UNSIGNED/CHECK)이 아니라 여기서 검출하는 이유는, S2 비교 실험 1 의 잔액 -2,000 이 관찰 대상이기 때문이다.
 * DB 가 막으면 그 실험이 사라진다.
 */
public final class Invariants {

  private final JdbcTemplate jdbcTemplate;

  public Invariants(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 세 불변식을 모두 검증한다. */
  public void assertAll() {
    assertWalletBalanceMatchesLedger();
    assertPaymentBalanceAmountMatchesCancels();
    assertCancelSumWithinApprovedAmount();
  }

  /** 불변식 1: 잔액 == 원장 합계, 그리고 음수가 아님. */
  public void assertWalletBalanceMatchesLedger() {
    List<Map<String, Object>> mismatches =
        jdbcTemplate.queryForList(
            "SELECT w.id, w.member_id, w.balance, COALESCE(SUM(l.amount), 0) AS ledger_sum"
                + " FROM wallet w LEFT JOIN wallet_ledger l ON l.wallet_id = w.id"
                + " GROUP BY w.id, w.member_id, w.balance"
                + " HAVING w.balance <> COALESCE(SUM(l.amount), 0) OR w.balance < 0");

    assertThat(mismatches)
        .as("불변식 1: wallet.balance == SUM(wallet_ledger.amount) 이고 음수가 아니어야 한다")
        .isEmpty();
  }

  /** 불변식 2: balance_amount == amount - 취소 합계. */
  public void assertPaymentBalanceAmountMatchesCancels() {
    List<Map<String, Object>> mismatches =
        jdbcTemplate.queryForList(
            "SELECT p.id, p.payment_key, p.amount, p.balance_amount,"
                + " COALESCE(SUM(c.cancel_amount), 0) AS cancel_sum"
                + " FROM payment p LEFT JOIN payment_cancel c ON c.payment_id = p.id"
                + " GROUP BY p.id, p.payment_key, p.amount, p.balance_amount"
                + " HAVING p.balance_amount <> p.amount - COALESCE(SUM(c.cancel_amount), 0)");

    assertThat(mismatches)
        .as("불변식 2: payment.balance_amount == amount - SUM(payment_cancel.cancel_amount)")
        .isEmpty();
  }

  /** 불변식 3: 취소 합계 <= 승인 금액. */
  public void assertCancelSumWithinApprovedAmount() {
    List<Map<String, Object>> violations =
        jdbcTemplate.queryForList(
            "SELECT p.id, p.payment_key, p.amount,"
                + " COALESCE(SUM(c.cancel_amount), 0) AS cancel_sum"
                + " FROM payment p LEFT JOIN payment_cancel c ON c.payment_id = p.id"
                + " GROUP BY p.id, p.payment_key, p.amount"
                + " HAVING COALESCE(SUM(c.cancel_amount), 0) > p.amount");

    assertThat(violations).as("불변식 3: 취소 합계는 승인 금액 이하여야 한다").isEmpty();
  }
}
