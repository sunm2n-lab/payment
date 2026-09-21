package com.sunm2n.payment.support;

import com.sunm2n.payment.infrastructure.FakeCardApprovalClient;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 부작용 비교용 상태 스냅샷.
 *
 * <p>거절된 요청이 아무것도 바꾸지 않았는지 확인할 때 쓴다. 요청 **직전과 직후**를 비교하며, Fake 카드사 호출 횟수는 "0" 이 아니라 "증가하지 않음" 으로
 * 본다. READY 아닌 상태의 승인이나 초과 취소 같은 케이스는 준비 단계에서 이미 카드 승인을 한 번 실행하기 때문이다.
 *
 * <p>건수와 합계만 비교하면 놓치는 변화가 있다. 예를 들어 거절 과정에서 {@code card_approval_no} 나 {@code approved_at} 이 바뀌거나
 * 원장의 연결 대상({@code wallet_id}/{@code payment_id})이 바뀌어도 건수·합계는 그대로다. 그래서 **관련 컬럼을 포함한 정렬된 행 목록**을
 * 통째로 비교한다. record 라 {@code equals} 로 한 번에 비교된다.
 *
 * <p>S1~S10 의 실패·재시도 검증에서도 그대로 쓴다.
 */
public record StateSnapshot(
    List<Map<String, Object>> wallets,
    List<Map<String, Object>> payments,
    List<Map<String, Object>> ledgers,
    List<Map<String, Object>> cancels,
    int cardApproveCount,
    int cardCancelCount) {

  public static StateSnapshot capture(
      JdbcTemplate jdbcTemplate, FakeCardApprovalClient fakeCardApprovalClient) {
    return new StateSnapshot(
        jdbcTemplate.queryForList("SELECT id, member_id, balance FROM wallet ORDER BY id"),
        jdbcTemplate.queryForList(
            "SELECT id, payment_key, order_id, merchant_id, wallet_id, method, amount,"
                + " balance_amount, status, approved_at, card_approval_no"
                + " FROM payment ORDER BY id"),
        jdbcTemplate.queryForList(
            "SELECT id, wallet_id, type, amount, payment_id FROM wallet_ledger ORDER BY id"),
        jdbcTemplate.queryForList(
            "SELECT id, payment_id, cancel_amount, reason FROM payment_cancel ORDER BY id"),
        fakeCardApprovalClient.getApproveCount(),
        fakeCardApprovalClient.getCancelCount());
  }
}
