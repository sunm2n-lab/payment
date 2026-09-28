package com.sunm2n.pay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunm2n.pay.idempotency.RequestHash;

/**
 * 취소 요청의 멱등 식별 — 어떤 필드를 어떤 순서로 넣어 "같은 요청" 을 판정하는가 ({@code docs/plan/S5.md} 4.1).
 *
 * <p>입력은 <b>고정 필드 순서의 JSON</b> {@code {"paymentKey":..., "cancelAmount":..., "reason":...}} 이다.
 * 구분자로 이어 붙이면 값에 구분자가 들어올 때 서로 다른 요청이 같은 문자열이 될 수 있다. {@code ObjectNode} 는 넣은 순서를 지킨다.
 *
 * <ul>
 *   <li>{@code reason} 의 {@code null} 과 {@code ""} 는 다른 요청이다. 저장되는 취소 사유가 다르다
 *   <li>{@code paymentKey} 를 넣는다. 같은 키로 다른 결제를 취소하려 하면 거절된다
 * </ul>
 *
 * <p>{@code idempotency} 패키지는 결제 타입을 모르므로 구성은 여기에 두고, 해시만 {@link RequestHash} 에 맡긴다.
 */
public final class CancelFingerprint {

  /** 키 범위 {@code (merchant_id, operation)} 의 operation 값. */
  public static final String OPERATION = "CANCEL";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CancelFingerprint() {}

  public static String hash(String paymentKey, long cancelAmount, String reason) {
    return RequestHash.sha256Hex(canonical(paymentKey, cancelAmount, reason));
  }

  static String canonical(String paymentKey, long cancelAmount, String reason) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("paymentKey", paymentKey);
    node.put("cancelAmount", cancelAmount);
    node.put("reason", reason);
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("문자열·정수만 담은 노드의 직렬화는 실패하지 않는다", e);
    }
  }
}
