package com.sunm2n.pay.idempotency;

/**
 * 저장된 키 행.
 *
 * <p>응답 본문은 문자열 그대로다. 무엇을 직렬화했는지는 호출하는 업무가 알고, 이 패키지는 결제·지갑 타입을 모른다 ({@code docs/plan/S5.md} 7절).
 * 1·2차 실패 배선이 남긴 행은 응답을 저장하지 않으므로 {@code responseStatus}·{@code responseBody} 가 null 이다.
 */
public record IdempotencyRecord(
    long id,
    String requestHash,
    IdempotencyStatus status,
    Integer responseStatus,
    String responseBody) {}
