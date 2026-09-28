package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * V2 스키마의 통합 테스트 베이스. 같은 컨테이너의 별도 데이터베이스에 Flyway 를 V2 까지만 적용한다.
 *
 * <p>"과거 실패 재현은 전용 DB 를 해당 버전으로 초기화하고, 최신 스키마를 공유하지 않는다" (SCENARIO 94행) 의 구현이다. S4 의 V3 가 {@code
 * wallet_ledger -> wallet} FK 를 더하면 naive·낙관적 전략의 "원장 INSERT -> 지갑 UPDATE" 가 FK 락 승격 데드락을 일으켜,
 * S1·S2 의 Lost Update 재현이 데드락 재현으로 바뀐다 ({@code docs/plan/S4.md} 1.1). V2 를 전제로 쓴 실험은 결과가 같더라도 여기서
 * 돌린다.
 *
 * <p>{@code @ServiceConnection} 을 쓰지 않는다. 서비스 연결 정보는 프로퍼티보다 우선하므로 URL 을 바꿀 수 없다. 대신 연결 정보를 직접 준다.
 * datasource 와 Flyway target 이 다르므로 최신 스키마와는 Spring 컨텍스트가 따로 뜬다 — 게이트·Fake 카드사도 따로다.
 *
 * <p>{@code ddl-auto: validate} 는 엔티티가 V2 에 없는 테이블·컬럼을 매핑하면 이 컨텍스트의 기동을 막는다. V3 는 FK 만 더하므로 문제가 없다.
 * 새 엔티티가 생기는 시나리오(S5)에서 다시 정한다.
 */
public abstract class AbstractV2SchemaTest extends AbstractDatabaseTest {

  private static final String DATABASE = "payment_v2";

  @DynamicPropertySource
  static void v2Schema(DynamicPropertyRegistry registry) {
    String url = MySqlTestContainer.createDatabase(DATABASE);
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", MySqlTestContainer.MYSQL::getUsername);
    registry.add("spring.datasource.password", MySqlTestContainer.MYSQL::getPassword);
    registry.add("spring.flyway.target", () -> "2");
  }
}
