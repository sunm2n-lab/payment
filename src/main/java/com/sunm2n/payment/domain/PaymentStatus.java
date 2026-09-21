package com.sunm2n.payment.domain;

/**
 * 결제 상태.
 *
 * <p>Phase 0 의 상태 전이는 {@code READY -> DONE} 직행이다. {@code IN_PROGRESS} 는 enum 에만 두고 S1(중복 승인)에서 조건부
 * UPDATE 와 함께 처음 쓴다.
 */
public enum PaymentStatus {
  READY,
  IN_PROGRESS,
  DONE,
  PARTIAL_CANCELED,
  CANCELED,
  ABORTED,
  EXPIRED
}
