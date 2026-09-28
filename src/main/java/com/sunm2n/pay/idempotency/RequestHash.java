package com.sunm2n.pay.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 정규화된 요청 문자열의 SHA-256 16진 표현 (64자, {@code request_hash CHAR(64)}).
 *
 * <p>무엇을 어떤 순서로 넣을지(정규화)는 호출하는 업무가 정한다. 이 클래스는 문자열만 받는다 — 취소 요청의 구성은 {@code CancelFingerprint} 가
 * 갖는다.
 */
public final class RequestHash {

  private RequestHash() {}

  public static String sha256Hex(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 은 모든 JVM 이 제공해야 하는 알고리즘이다", e);
    }
  }
}
