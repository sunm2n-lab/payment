package com.sunm2n.pay.payment.infrastructure.card;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 항상 성공하는 인프로세스 Fake.
 *
 * <p>호출 횟수를 기록한다. S1 의 "Fake 승인 호출·승인번호가 여러 개" 관찰과 Phase 0 의 "승인 횟수" 검증, 실패 규약 테스트의 "거절 후 부작용
 * 없음"(호출 횟수가 증가하지 않았는지)이 이 카운터를 쓴다.
 *
 * <p>싱글턴 빈이므로 카운터는 테스트 간 정리 규약에서 매 테스트마다 리셋한다.
 */
@Component
public class FakeCardApprovalClient implements CardApprovalClient {

  private final AtomicInteger approveCount = new AtomicInteger();
  private final AtomicInteger cancelCount = new AtomicInteger();

  @Override
  public CardApproval approve(String paymentKey, long amount) {
    approveCount.incrementAndGet();
    return new CardApproval("fake-" + UUID.randomUUID());
  }

  @Override
  public void cancel(String cardApprovalNo, long cancelAmount) {
    cancelCount.incrementAndGet();
  }

  public int getApproveCount() {
    return approveCount.get();
  }

  public int getCancelCount() {
    return cancelCount.get();
  }

  public void reset() {
    approveCount.set(0);
    cancelCount.set(0);
  }
}
