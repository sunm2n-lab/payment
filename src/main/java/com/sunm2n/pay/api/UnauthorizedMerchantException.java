package com.sunm2n.pay.api;

/**
 * {@code X-API-Key} 가 없거나 등록되지 않은 키다.
 *
 * <p>인증은 도메인 규칙이 아니라 api 계층의 관심사이므로 {@code DomainException} 을 상속하지 않는다.
 */
public class UnauthorizedMerchantException extends RuntimeException {

  public UnauthorizedMerchantException(String message) {
    super(message);
  }
}
