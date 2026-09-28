package com.sunm2n.pay.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 모든 통합 테스트가 공유하는 MySQL 컨테이너. JVM 당 하나만 띄운다.
 *
 * <p>{@code @Testcontainers}/{@code @Container} 를 쓰지 않는 이유: Jupiter 확장이 컨테이너를 클래스 단위로 start/stop
 * 하므로, 첫 테스트 클래스가 끝나면 컨테이너가 멈추고 캐시된 Spring 컨텍스트의 DataSource 가 죽은 포트를 가리키게 된다. 그래서 static 초기화 블록에서
 * 직접 start 하고, 종료는 Testcontainers 의 Ryuk 에 맡긴다.
 *
 * <p>S4 에서 베이스 클래스에서 떼어 냈다. 최신 스키마 DB 와 과거 스키마 DB({@link AbstractV2SchemaTest})가 <b>같은 컨테이너</b>의 서로
 * 다른 데이터베이스를 쓰기 때문이다. 컨테이너를 둘 띄우면 MySQL 버전·설정이 같다는 것을 따로 보장해야 한다.
 */
public final class MySqlTestContainer {

  public static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0.44"));

  static {
    MYSQL.start();
  }

  private MySqlTestContainer() {}

  /**
   * 같은 컨테이너에 데이터베이스를 하나 더 만들고 테스트 사용자에게 권한을 준다.
   *
   * <p>테스트 사용자는 컨테이너가 만든 기본 데이터베이스에만 권한이 있으므로 root 로 만든다. Testcontainers 는 root 비밀번호를 테스트 사용자의
   * 비밀번호와 같게 둔다. 이미 있으면 그대로 둔다 — Flyway 이력이 남아 있으므로 다시 마이그레이션하지 않는다.
   *
   * @return 그 데이터베이스를 가리키는 JDBC URL
   */
  public static String createDatabase(String name) {
    try (Connection connection =
            DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE IF NOT EXISTS " + name);
      statement.execute(
          "GRANT ALL PRIVILEGES ON " + name + ".* TO '" + MYSQL.getUsername() + "'@'%'");
    } catch (SQLException e) {
      throw new IllegalStateException("테스트 데이터베이스를 만들지 못했습니다: " + name, e);
    }
    return MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + name);
  }
}
