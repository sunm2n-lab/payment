package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 테스트 컨텍스트의 격리 수준. 컨텍스트마다 풀이 커넥션을 만들 때 세션 값으로 정한다 ({@code docs/plan/S7_PREP.md} 2절).
 *
 * <p>S6 까지는 서버 기본값(REPEATABLE READ)에 기대고 있었다. 컨테이너를 공유하므로 서버 전역 값을 바꾸면 모든 베이스가 함께 바뀐다. 세션 범위인
 * Hikari 프로퍼티는 다른 컨텍스트로 새지 않는다.
 *
 * <p>Hikari 프로퍼티와 MySQL 세션 변수의 표기가 다르므로 한곳에 둔다. SQL 의 {@code SET SESSION TRANSACTION ISOLATION
 * LEVEL} 로 바꾸지 않는다 — HikariCP 는 SQL 로 바꾼 세션 값을 반환 때 되돌리지 않는다.
 */
public enum TestIsolation {
  REPEATABLE_READ("TRANSACTION_REPEATABLE_READ", "REPEATABLE-READ"),
  READ_COMMITTED("TRANSACTION_READ_COMMITTED", "READ-COMMITTED");

  private final String hikariValue;
  private final String sessionValue;

  TestIsolation(String hikariValue, String sessionValue) {
    this.hikariValue = hikariValue;
    this.sessionValue = sessionValue;
  }

  /** {@code @@transaction_isolation} 이 돌려주는 값. 전제 단언에 쓴다. */
  public String sessionValue() {
    return sessionValue;
  }

  void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.transaction-isolation", () -> hikariValue);
  }
}
