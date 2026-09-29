package com.sunm2n.pay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunm2n.pay.idempotency.RequestHash;

/**
 * 주문 기반 취소 요청의 멱등 식별 ({@code docs/plan/S6.md} 4.3). 규칙은 {@link CancelFingerprint} 와 같고 첫 필드만 다르다.
 *
 * <p>입력은 고정 필드 순서의 JSON {@code {"orderId":..., "cancelAmount":..., "reason":...}} 이다. {@code
 * operation} 은 {@link CancelFingerprint#OPERATION} 그대로라 키 범위를 {@code paymentKey} 취소와 공유한다. 그래서 같은
 * 키를 두 취소 경로에 쓰면 첫 필드 이름부터 달라 hash 가 다르고, 409 {@code IDEMPOTENCY_KEY_REUSED} 다. 같은 결제를 가리키더라도 다른
 * 요청이다.
 */
public final class OrderCancelFingerprint {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OrderCancelFingerprint() {}

  public static String hash(String orderId, long cancelAmount, String reason) {
    return RequestHash.sha256Hex(canonical(orderId, cancelAmount, reason));
  }

  static String canonical(String orderId, long cancelAmount, String reason) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("orderId", orderId);
    node.put("cancelAmount", cancelAmount);
    node.put("reason", reason);
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("문자열·정수만 담은 노드의 직렬화는 실패하지 않는다", e);
    }
  }
}
