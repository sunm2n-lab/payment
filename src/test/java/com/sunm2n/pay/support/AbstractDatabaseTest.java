package com.sunm2n.pay.support;

import com.sunm2n.pay.bootstrap.seed.SeedRunner;
import com.sunm2n.pay.payment.infrastructure.card.FakeCardApprovalClient;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 베이스의 공통 부분 — 주입받는 도구와 테스트 간 정리 규약.
 *
 * <p>어느 데이터베이스에 붙는지는 하위 베이스가 정한다. 최신 스키마는 {@link AbstractIntegrationTest}, 과거 스키마는 {@link
 * PastSchemaDatabase} 로 등록하는 버전별 베이스({@link AbstractV2SchemaTest} 등)다. 테스트 클래스는 이 클래스를 직접 상속하지 않는다.
 *
 * <p>컨테이너({@link MySqlTestContainer})를 공유하므로 DB 는 테스트 간에 오염된다. 격리는 아래 정리 규약이 담당한다. 같은 이유로 JUnit 병렬
 * 실행을 켜지 않고 Gradle {@code maxParallelForks} 도 1 로 둔다.
 */
@SpringBootTest
@Import(ConcurrencyGateConfig.class)
public abstract class AbstractDatabaseTest {

  @Autowired protected JdbcTemplate jdbcTemplate;

  @Autowired protected DataSource dataSource;

  @Autowired protected FakeCardApprovalClient fakeCardApprovalClient;

  @Autowired protected ConcurrencyGate concurrencyGate;

  protected Invariants invariants;

  /**
   * 테스트 간 정리 규약: 미종료 실행 검사 -> 동기화 게이트 해제 -> 전 테이블 TRUNCATE -> 시드 재삽입 -> Fake 카운터 리셋.
   *
   * <p><b>검사가 먼저다.</b> 앞 테스트의 실행이 끝나지 않았으면 TRUNCATE 하지 않고 여기서 실패한다. 뒤늦게 커밋하는 워커가 이 정리와 겹치면, 원인이 앞
   * 테스트에 있는데 다른 테스트가 깨진다. 실행 종료는 {@link HeldRun} 과 러너가 확인한다 ({@code docs/plan/S7_PREP.md} 4.3).
   *
   * <p><b>게이트 해제는 검사가 실패해도 한다.</b> 붙잡힌 채 남은 워커를 풀어 줘야 그 실행이 끝나고 뒤 테스트가 다시 진행할 수 있다. 해제가 종료를 보장하지는
   * 않는다 — 풀린 워커가 커밋까지 가는 데 시간이 걸린다. 해제를 실행 헬퍼가 아니라 여기에도 두는 이유는, 게이트를 무장한 테스트가 동시 실행에 도달하기 전에 실패할 수도
   * 있기 때문이다.
   *
   * <p>시드는 {@code local}/{@code load} 프로파일 러너와 같은 {@code seed_local.sql} 을 쓴다. TRUNCATE 뒤라 항상 삽입
   * 경로를 타며, 시드의 멱등 구문이 있어도 해가 없다.
   */
  @BeforeEach
  void resetDatabaseAndFakes() {
    try {
      ConcurrentRunner.verifyNoUnfinishedRuns();
    } finally {
      concurrencyGate.reset();
    }
    DatabaseCleaner.truncateAll(jdbcTemplate);
    SeedRunner.executeSeed(dataSource);
    fakeCardApprovalClient.reset();
    invariants = new Invariants(jdbcTemplate);
  }

  /** 시드 가맹점의 id. api_key 는 시드가 고정하지만 id 는 AUTO_INCREMENT 라 매번 달라진다. */
  protected Long merchantIdOf(String apiKey) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM merchant WHERE api_key = ?", Long.class, apiKey);
  }
}
