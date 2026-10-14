package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 테스트 컨텍스트의 커넥션 풀 설정. 컨텍스트 하나가 풀 하나이고, 모든 컨텍스트가 컨테이너 하나를 공유한다.
 *
 * <p>HikariCP 의 {@code minimum-idle} 기본값은 {@code maximum-pool-size} 와 같아 컨텍스트가 뜨는 즉시 10개를 연다. 캐시된
 * 컨텍스트가 늘 때마다 컨테이너의 연결이 10씩 쌓인다 — S6 까지 7개 컨텍스트로 최대 72 ({@code docs/plan/S7_PREP.md} 1.1). 테스트에서만
 * 유휴 연결을 줄인다.
 *
 * <p>{@code maximum-pool-size} 는 줄이지 않는다. 대기 행렬 11번이 선행 1 + 후속 7 = 워커 8개로 연결을 동시에 쓴다. 최대를 줄이면 실험이
 * 커넥션 획득 대기로 바뀐다. {@code src/test/resources/application.yml} 을 두지 않는 이유는 같은 이름이라 main 의 설정을 통째로 가리기
 * 때문이다.
 */
final class TestPool {

  static final int MINIMUM_IDLE = 2;

  private TestPool() {}

  static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.minimum-idle", () -> MINIMUM_IDLE);
  }
}
