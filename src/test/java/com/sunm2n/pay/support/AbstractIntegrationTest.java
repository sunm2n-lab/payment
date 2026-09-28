package com.sunm2n.pay.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;

/**
 * 최신 스키마의 통합 테스트 베이스. Flyway 가 모든 마이그레이션을 적용한 DB 에 붙는다.
 *
 * <p>본선의 회귀·계약 테스트는 이 베이스를 쓴다. 과거 스키마를 전제로 쓴 재현은 {@link PastSchemaDatabase} 로 등록하는 버전별 베이스를 쓴다.
 */
public abstract class AbstractIntegrationTest extends AbstractDatabaseTest {

  @ServiceConnection protected static final MySQLContainer<?> MYSQL = MySqlTestContainer.MYSQL;
}
