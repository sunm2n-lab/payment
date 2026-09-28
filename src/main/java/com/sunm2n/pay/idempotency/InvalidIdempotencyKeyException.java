package com.sunm2n.pay.idempotency;

import com.sunm2n.pay.common.exception.DomainException;

/** {@code Idempotency-Key} 헤더 형식 오류 — 400 {@code INVALID_REQUEST}. */
public class InvalidIdempotencyKeyException extends DomainException {

  public InvalidIdempotencyKeyException(String reason) {
    super("Idempotency-Key: " + reason);
  }
}
