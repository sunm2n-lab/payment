package com.sunm2n.payment.infrastructure;

/**
 * 카드사 승인/취소 연동.
 *
 * <p>Phase 0 은 인프로세스 Fake 하나만 둔다. 지연 주입과 타임아웃 명시는 S8, WireMock 컨테이너 교체는 S9 다.
 */
public interface CardApprovalClient {

  /** 승인하고 승인번호를 발급받는다. */
  CardApproval approve(String paymentKey, long amount);

  void cancel(String cardApprovalNo, long cancelAmount);
}
