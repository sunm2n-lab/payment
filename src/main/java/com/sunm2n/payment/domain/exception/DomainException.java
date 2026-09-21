package com.sunm2n.payment.domain.exception;

/**
 * 도메인 규칙 위반의 공통 부모.
 *
 * <p>HTTP 상태 코드는 이 계층이 알지 않는다. {@code ApiExceptionHandler} 가 구체 예외마다 명시적으로 매핑한다. 이후 모든 시나리오의 완료 기준이
 * "예상한 실패(4xx) / 예상 밖 오류(5xx)" 구분에 의존하므로, 매핑을 자동 추론에 맡기지 않고 눈으로 확인할 수 있게 둔다.
 */
public abstract class DomainException extends RuntimeException {

  protected DomainException(String message) {
    super(message);
  }
}
