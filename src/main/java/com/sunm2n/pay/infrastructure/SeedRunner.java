package com.sunm2n.pay.infrastructure;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로컬/부하 실행용 시드 러너.
 *
 * <p>시드를 Flyway 이력에 넣지 않는 이유는 {@code db/seed/seed_local.sql} 주석에 있다. 러너는 컨텍스트 초기화가 끝난 뒤 실행되므로
 * Flyway 완료 후라는 순서가 보장된다.
 *
 * <p>테스트는 이 빈을 등록하지 않고, 테스트 간 정리 훅이 같은 파일을 직접 실행한다. 시드 경로는 하나다.
 */
@Component
@Profile({"local", "load"})
public class SeedRunner implements ApplicationRunner {

  public static final String SEED_LOCATION = "db/seed/seed_local.sql";

  private final DataSource dataSource;
  private final TransactionTemplate transactionTemplate;

  public SeedRunner(DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.dataSource = dataSource;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public void run(ApplicationArguments args) {
    transactionTemplate.executeWithoutResult(status -> executeSeed(dataSource));
  }

  /** 시드 스크립트를 현재 트랜잭션의 커넥션으로 실행한다. 테스트 정리 훅도 같은 파일을 쓴다. */
  public static void executeSeed(DataSource dataSource) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(SEED_LOCATION));
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
