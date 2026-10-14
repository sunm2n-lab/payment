package com.sunm2n.pay.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 최신 스키마의 통합 테스트 베이스. Flyway 가 모든 마이그레이션을 적용한 DB 에 붙는다.
 *
 * <p>본선의 회귀·계약 테스트는 이 베이스를 쓴다. 과거 스키마를 전제로 쓴 재현은 {@link PastSchemaDatabase} 로 등록하는 버전별 베이스를 쓴다.
 *
 * <p>연결 정보는 서비스 연결이, 격리 수준과 유휴 연결 수는 Hikari 프로퍼티가 정한다. 본선의 격리 수준을 어디에 둘지는 S7 이 정하므로 {@code
 * application.yml} 은 건드리지 않는다.
 */
public abstract class AbstractIntegrationTest extends AbstractDatabaseTest {

  @ServiceConnection protected static final MySQLContainer<?> MYSQL = MySqlTestContainer.MYSQL;

  @DynamicPropertySource
  static void hikari(DynamicPropertyRegistry registry) {
    TestIsolation.REPEATABLE_READ.register(registry);
    TestPool.register(registry);
  }
}
