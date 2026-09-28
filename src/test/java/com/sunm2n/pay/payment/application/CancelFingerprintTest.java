package com.sunm2n.pay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelFingerprintTest {

  @Test
  @DisplayName("고정 필드 순서의 JSON 으로 정규화한다")
  void canonicalIsFixedOrderJson() {
    assertThat(CancelFingerprint.canonical("pk", 3_000L, "사유"))
        .isEqualTo("{\"paymentKey\":\"pk\",\"cancelAmount\":3000,\"reason\":\"사유\"}");
  }

  @Test
  @DisplayName("reason 의 null 과 빈 문자열은 다른 요청이다")
  void nullAndEmptyReasonDiffer() {
    assertThat(CancelFingerprint.hash("pk", 3_000L, null))
        .isNotEqualTo(CancelFingerprint.hash("pk", 3_000L, ""));
  }

  @Test
  @DisplayName("같은 입력은 같은 64자 16진 해시다")
  void sameInputSameHash() {
    String hash = CancelFingerprint.hash("pk", 3_000L, "사유");

    assertThat(hash).isEqualTo(CancelFingerprint.hash("pk", 3_000L, "사유")).hasSize(64);
    assertThat(hash).matches("[0-9a-f]{64}");
  }
}
