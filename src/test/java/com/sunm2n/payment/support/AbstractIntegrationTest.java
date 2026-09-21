package com.sunm2n.payment.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 통합 테스트의 베이스. MySQL 컨테이너를 JVM 당 하나만 띄워 모든 테스트 클래스가 공유한다.
 *
 * <p>{@code @Testcontainers}/{@code @Container} 를 쓰지 않는 이유: Jupiter 확장이 컨테이너를 클래스 단위로 start/stop
 * 하므로, 첫 테스트 클래스가 끝나면 컨테이너가 멈추고 캐시된 Spring 컨텍스트의 DataSource 가 죽은 포트를 가리키게 된다. 그래서 static 초기화 블록에서
 * 직접 start 하고, 종료는 Testcontainers 의 Ryuk 에 맡긴다.
 *
 * <p>컨테이너를 공유하므로 DB 는 테스트 간에 오염된다. 격리는 테스트 간 정리 규약(TRUNCATE + 시드)이 담당한다.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0.44"));

  static {
    MYSQL.start();
  }
}
