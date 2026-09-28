package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.support.AbstractIntegrationTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 최신 컨텍스트가 정말 최신 스키마에서 도는지 확인한다. {@link V2SchemaContextTest} 의 짝이다.
 *
 * <p>과거 컨텍스트가 늘면서({@code docs/plan/S5.md} 5절) 등록 배선을 헬퍼로 옮겼다. 최신 컨텍스트가 조용히 과거 DB 로 붙거나 Flyway 가 일부만
 * 적용되면 본선 회귀가 green 인 채로 과거 스키마를 검증하게 된다.
 */
class LatestSchemaContextTest extends AbstractIntegrationTest {

  private static final Pattern MIGRATION = Pattern.compile("V(\\d+)__.*\\.sql");

  @Test
  @DisplayName("컨테이너의 기본 데이터베이스에 붙는다")
  void usesContainerDatabase() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

    assertThat(database).isEqualTo(MYSQL.getDatabaseName());
  }

  @Test
  @DisplayName("Flyway 는 마지막 마이그레이션까지 적용했다")
  void migratedUpToLatest() throws IOException {
    String version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success = 1"
                + " ORDER BY installed_rank DESC LIMIT 1",
            String.class);

    assertThat(version).isEqualTo(String.valueOf(latestMigrationVersion()));
  }

  private static int latestMigrationVersion() throws IOException {
    return Arrays.stream(
            new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql"))
        .map(resource -> MIGRATION.matcher(Objects.requireNonNull(resource.getFilename())))
        .filter(Matcher::matches)
        .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
        .max()
        .orElseThrow();
  }
}
