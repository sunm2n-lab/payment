package com.sunm2n.pay.idempotency;

import com.sunm2n.pay.common.exception.DomainException;

/**
 * 같은 키에 다른 요청 내용 — 409 {@code IDEMPOTENCY_KEY_REUSED}. 저장된 응답을 돌려주지 않는다.
 *
 * <p>비교 대상은 성공해 저장된 요청뿐이다. 업무 실패로 롤백된 요청은 키 행이 남지 않으므로, 그 뒤 같은 키로 다른 내용을 보내면 새 요청으로 실행된다.
 */
public class IdempotencyKeyReusedException extends DomainException {

  public IdempotencyKeyReusedException(String idempotencyKey) {
    super("이미 다른 요청에 사용된 멱등키입니다. key=" + idempotencyKey);
  }
}
