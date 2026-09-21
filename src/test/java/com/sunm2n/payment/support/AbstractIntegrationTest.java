package com.sunm2n.payment.support;

import com.sunm2n.payment.infrastructure.FakeCardApprovalClient;
import com.sunm2n.payment.infrastructure.SeedRunner;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 통합 테스트의 베이스. MySQL 컨테이너를 JVM 당 하나만 띄워 모든 테스트 클래스가 공유한다.
 *
 * <p>{@code @Testcontainers}/{@code @Container} 를 쓰지 않는 이유: Jupiter 확장이 컨테이너를 클래스 단위로 start/stop
 * 하므로, 첫 테스트 클래스가 끝나면 컨테이너가 멈추고 캐시된 Spring 컨텍스트의 DataSource 가 죽은 포트를 가리키게 된다. 그래서 static 초기화 블록에서
 * 직접 start 하고, 종료는 Testcontainers 의 Ryuk 에 맡긴다.
 *
 * <p>컨테이너를 공유하므로 DB 는 테스트 간에 오염된다. 격리는 아래 정리 규약이 담당한다. 같은 이유로 JUnit 병렬 실행을 켜지 않고 Gradle {@code
 * maxParallelForks} 도 1 로 둔다.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0.44"));

  static {
    MYSQL.start();
  }

  @Autowired protected JdbcTemplate jdbcTemplate;

  @Autowired protected DataSource dataSource;

  @Autowired protected FakeCardApprovalClient fakeCardApprovalClient;

  protected Invariants invariants;

  /**
   * 테스트 간 정리 규약: 전 테이블 TRUNCATE -> 시드 재삽입 -> Fake 카운터 리셋.
   *
   * <p>시드는 {@code local}/{@code load} 프로파일 러너와 같은 {@code seed_local.sql} 을 쓴다. TRUNCATE 뒤라 항상 삽입
   * 경로를 타며, 시드의 멱등 구문이 있어도 해가 없다.
   */
  @BeforeEach
  void resetDatabaseAndFakes() {
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
