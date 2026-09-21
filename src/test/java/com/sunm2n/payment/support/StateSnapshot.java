package com.sunm2n.payment.support;

import com.sunm2n.payment.infrastructure.FakeCardApprovalClient;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 부작용 비교용 상태 스냅샷.
 *
 * <p>거절된 요청이 아무것도 바꾸지 않았는지 확인할 때 쓴다. 요청 **직전과 직후**를 비교하며, Fake 카드사 호출 횟수는 "0" 이 아니라 "증가하지 않음" 으로
 * 본다. READY 아닌 상태의 승인이나 초과 취소 같은 케이스는 준비 단계에서 이미 카드 승인을 한 번 실행하기 때문이다.
 *
 * <p>record 라 {@code equals} 로 통째로 비교된다. S1~S10 의 실패·재시도 검증에서도 그대로 쓴다.
 */
public record StateSnapshot(
    String wallets,
    String payments,
    int ledgerCount,
    long ledgerSum,
    int cancelCount,
    long cancelSum,
    int cardApproveCount,
    int cardCancelCount) {

  public static StateSnapshot capture(
      JdbcTemplate jdbcTemplate, FakeCardApprovalClient fakeCardApprovalClient) {
    return new StateSnapshot(
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(GROUP_CONCAT(CONCAT(member_id, ':', balance)"
                + " ORDER BY member_id SEPARATOR '|'), '') FROM wallet",
            String.class),
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(GROUP_CONCAT(CONCAT(payment_key, ':', status, ':', balance_amount)"
                + " ORDER BY id SEPARATOR '|'), '') FROM payment",
            String.class),
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_ledger", Integer.class),
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM wallet_ledger", Long.class),
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_cancel", Integer.class),
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(cancel_amount), 0) FROM payment_cancel", Long.class),
        fakeCardApprovalClient.getApproveCount(),
        fakeCardApprovalClient.getCancelCount());
  }
}
