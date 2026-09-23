package com.sunm2n.pay.application;

import com.sunm2n.pay.domain.Payment;

/**
 * 승인 구현.
 *
 * <p>실패 버전과 개선 버전을 <b>코드에 공존</b>시키기 위한 이음매다. 과거 실습 기록을 언제든 다시 실행할 수 있어야 하므로, 개선했다고 해서 결함을 품은 구현을
 * 지우지 않는다. 테스트가 빈을 골라 쓴다 ({@code docs/plan/PHASE0.md} 2절).
 *
 * <p>구현체가 트랜잭션 경계를 소유한다. 조회부터 상태 전이·승인·DONE 저장까지 한 트랜잭션이며, 경계 분리는 S8 이다.
 */
public interface PaymentConfirmer {

  Payment confirm(Long merchantId, String paymentKey, String orderId, long amount);
}
