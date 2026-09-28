package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 과거 스키마 DB 를 Spring 컨텍스트에 등록한다. 같은 컨테이너에 별도 데이터베이스를 만들고 Flyway 를 주어진 버전까지만 적용한다.
 *
 * <p>"과거 실패 재현은 전용 DB 를 해당 버전으로 초기화하고, 최신 스키마를 공유하지 않는다" (SCENARIO 94행) 의 구현이다. S4 는 V2 하나였고, S5 에서
 * V4 가 더해졌다 ({@code docs/plan/S5.md} 5절).
 *
 * <p><b>버전을 공유 static 상태로 두지 않는다.</b> 각 베이스가 자기 {@code @DynamicPropertySource} 에서 값을 넘긴다.
 * datasource 와 Flyway target 이 다르므로 Spring 컨텍스트 캐시 키가 버전별로 갈린다 — 게이트·Fake 카드사도 따로다.
 *
 * <p>{@code @ServiceConnection} 을 쓰지 않는다. 서비스 연결 정보는 프로퍼티보다 우선하므로 URL 을 바꿀 수 없다. 대신 연결 정보를 직접 준다.
 *
 * <p>{@code ddl-auto: validate} 는 과거 컨텍스트에서도 유지한다. 엔티티가 과거 스키마에 없는 테이블·컬럼을 매핑하면 기동이 막히는데, S5 의 키
 * 저장소는 JDBC 전용이라 그런 엔티티가 생기지 않는다. 과거 엔티티 매핑이 그 스키마와 맞는지 계속 검증하는 쪽이 낫다.
 */
public final class PastSchemaDatabase {

  private PastSchemaDatabase() {}

  public static void register(
      DynamicPropertyRegistry registry, String database, String flywayTarget) {
    String url = MySqlTestContainer.createDatabase(database);
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", MySqlTestContainer.MYSQL::getUsername);
    registry.add("spring.datasource.password", MySqlTestContainer.MYSQL::getPassword);
    registry.add("spring.flyway.target", () -> flywayTarget);
  }
}
