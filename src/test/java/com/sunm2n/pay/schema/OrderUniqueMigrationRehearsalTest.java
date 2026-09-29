package com.sunm2n.pay.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.support.MySqlTestContainer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S6 V7 적용 전 데이터 정리 리허설 ({@code docs/plan/S6.md} 4.6).
 *
 * <p>전용 DB 에서 V6 까지 적용하고 사례별 데이터를 넣은 뒤 V7 을 적용해 본다. Spring 컨텍스트 없이 Flyway 를 직접 부른다 — 같은 컨테이너의 다른
 * 데이터베이스이고, 사례마다 새로 만든다.
 *
 * <p>정리 절차는 {@code docker/mysql/scripts/s6-order-cleanup.sql} 을 {@code @step} 단위로 읽어 그대로 실행한다. 로컬
 * DB 에 쓰는 것과 같은 SQL 이다.
 *
 * <p>V7 이 스스로 걸러 주는 것은 중복 주문과 비ASCII 두 가지뿐이다. ASCII 형식 위반은 그대로 통과하므로 확인 1 이 필수 사전 조건이다.
 */
class OrderUniqueMigrationRehearsalTest {

  private static final String DATABASE = "payment_rehearsal";
  private static final Path CLEANUP = Path.of("docker/mysql/scripts/s6-order-cleanup.sql");

  private Connection connection;
  private long nextId = 1;

  @BeforeEach
  void freshDatabaseAtV6() throws SQLException {
    try (Connection root = root();
        Statement statement = root.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
    }
    String url = MySqlTestContainer.createDatabase(DATABASE);
    flyway("6").migrate();
    connection =
        DriverManager.getConnection(
            url, MySqlTestContainer.MYSQL.getUsername(), MySqlTestContainer.MYSQL.getPassword());
  }

  @AfterEach
  void close() throws SQLException {
    connection.close();
  }

  @Test
  @DisplayName("리허설 1 - 중복 주문이 있으면 V7 은 실패하고 스키마는 V6 그대로다")
  void duplicateOrderFailsV7() throws SQLException {
    insert(1, "order-dup-1");
    insert(1, "order-dup-1");

    assertThatThrownBy(() -> flyway("7").migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("Duplicate entry");

    assertStillV6();
    System.out.println("[S6 리허설 1] 이력 " + history());
  }

  @Test
  @DisplayName("리허설 2 - 비ASCII 주문 id 가 있으면 V7 은 실패하고 스키마는 V6 그대로다")
  void nonAsciiFailsV7() throws SQLException {
    insert(1, "café-order");

    assertThatThrownBy(() -> flyway("7").migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("Incorrect string value");

    assertStillV6();
  }

  @Test
  @DisplayName("리허설 3 - ASCII 형식 위반만 있으면 V7 은 성공한다 - 막지 못한다. 확인 1 이 잡고, 뒤 공백 행은 공백 없는 조회에 잡힌다")
  void asciiFormatViolationsPassV7() throws SQLException {
    long slash = insert(1, "a/bcdef");
    long space = insert(1, "a bcdef");
    long trailing = insert(1, "xyzabc ");
    long tooShort = insert(1, "short");

    assertThat(ids(step("check1"))).containsExactlyInAnyOrder(slash, space, trailing, tooShort);

    flyway("7").migrate();

    assertThat(PaymentSchemaOf.indexNames(connection)).contains("uk_payment_merchant_order");
    assertThat(longs("SELECT id FROM payment WHERE merchant_id = 1 AND order_id = 'xyzabc'"))
        .as("ascii_bin 은 뒤 공백을 무시한다 - 유효한 입력 xyzabc 가 옛 결제를 고른다")
        .containsExactly(trailing);
    assertThatThrownBy(() -> insert(1, "xyzabc"))
        .as("xyzabc 로 생성하면 1062")
        .hasMessageContaining("Duplicate entry");
  }

  @Test
  @DisplayName(
      "리허설 4 - 'aaaaaa' 와 'aaaaaa ' 는 확인 2 로는 중복이 아닌데 V7 은 실패한다. 확인 1 이 'aaaaaa ' 를 잡는다 - 순서를 지키는 이유")
  void trailingSpaceDuplicateNeedsFormatFirst() throws SQLException {
    insert(1, "aaaaaa");
    long padded = insert(1, "aaaaaa ");

    assertThat(query(step("check2"))).as("확인 2 (utf8mb4_0900_bin, NO PAD)").isEmpty();
    assertThat(ids(step("check1"))).containsExactly(padded);
    assertThatThrownBy(() -> flyway("7").migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("Duplicate entry");
    assertStillV6();
  }

  @Test
  @DisplayName("리허설 5 - 새 값(fixed-{id})을 이미 쓰는 주문이 있으면 충돌 검사가 잡는다. 적용하지 않는다")
  void collisionWithExistingOrderIsDetected() throws SQLException {
    long broken = insert(1, "a/bcdef");
    insert(1, "fixed-" + broken);

    run(step("format.prepare"));
    List<Map<String, Object>> collisions = query(step("collisions.existing"));

    assertThat(collisions)
        .singleElement()
        .satisfies(c -> assertThat(c.get("id")).isEqualTo(broken));
    assertThat(query(step("collisions.new"))).isEmpty();
    run(step("cleanup"));
    assertThat(longs("SELECT id FROM payment WHERE order_id = 'a/bcdef'"))
        .as("적용하지 않았다")
        .containsExactly(broken);
  }

  @Test
  @DisplayName("리허설 6 - ① 형식 정상화 → ② 중복 정리 순서로 정리하면 확인 1·2 가 0행이고 V7 이 성공한다")
  void cleanupInOrderThenV7Succeeds() throws SQLException {
    insert(1, "order-keep-1");
    long dupKeep = insert(1, "order-dup-1");
    long dupFix = insert(1, "order-dup-1");
    long slash = insert(1, "a/bcdef");
    insert(1, "aaaaaa");
    long padded = insert(1, "aaaaaa ");
    long other = insert(2, "order-dup-1");

    run(step("format.prepare"));
    assertThat(query(step("collisions.existing"))).isEmpty();
    assertThat(query(step("collisions.new"))).isEmpty();
    run(step("apply"));
    assertThat(query(step("check1"))).isEmpty();

    run(step("duplicates.prepare"));
    assertThat(query(step("collisions.existing"))).isEmpty();
    assertThat(query(step("collisions.new"))).isEmpty();
    run(step("apply"));
    assertThat(query(step("check2"))).isEmpty();
    run(step("cleanup"));

    flyway("7").migrate();

    assertThat(orderIdOf(dupKeep)).as("묶음마다 id 가 가장 작은 행을 남긴다").isEqualTo("order-dup-1");
    assertThat(orderIdOf(dupFix)).isEqualTo("fixed-" + dupFix);
    assertThat(orderIdOf(slash)).isEqualTo("fixed-" + slash);
    assertThat(orderIdOf(padded)).isEqualTo("fixed-" + padded);
    assertThat(orderIdOf(other)).as("다른 가맹점의 같은 주문은 중복이 아니다").isEqualTo("order-dup-1");
    assertThat(PaymentSchemaOf.orderIdCollation(connection)).isEqualTo("ascii_bin");
  }

  @Test
  @DisplayName("리허설 7 - 실패한 V7 은 Flyway 이력에 실패로 남아, 정리한 뒤에도 repair 없이는 다시 적용되지 않는다")
  void failedMigrationNeedsRepair() throws SQLException {
    insert(1, "order-dup-1");
    long dupFix = insert(1, "order-dup-1");
    assertThatThrownBy(() -> flyway("7").migrate()).isInstanceOf(FlywayException.class);
    List<Map<String, Object>> afterFailure = history();
    System.out.println("[S6 리허설 7] 실패 뒤 이력 " + afterFailure);

    connection
        .createStatement()
        .executeUpdate("UPDATE payment SET order_id = 'fixed-" + dupFix + "' WHERE id = " + dupFix);
    Throwable retry = catchMigrate();
    System.out.println("[S6 리허설 7] repair 없이 재적용: " + (retry == null ? "성공" : retry.getMessage()));

    assertThat(afterFailure)
        .as("V7 이 success = 0 으로 남는다")
        .anySatisfy(
            row -> {
              assertThat(row.get("version")).isEqualTo("7");
              assertThat(row.get("success")).isIn(false, 0, 0L);
            });
    assertThat(retry).as("repair 없이는 validate 에서 막힌다").isNotNull();

    flyway("7").repair();
    flyway("7").migrate();
    assertThat(PaymentSchemaOf.indexNames(connection)).contains("uk_payment_merchant_order");
    System.out.println("[S6 리허설 7] repair 뒤 이력 " + history());
  }

  private void assertStillV6() throws SQLException {
    assertThat(PaymentSchemaOf.indexNames(connection))
        .contains("idx_payment_merchant_id")
        .doesNotContain("uk_payment_merchant_order");
    assertThat(PaymentSchemaOf.orderIdCollation(connection)).isEqualTo("utf8mb4_0900_ai_ci");
  }

  private Throwable catchMigrate() {
    try {
      flyway("7").migrate();
      return null;
    } catch (FlywayException e) {
      return e;
    }
  }

  private static Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(
            MySqlTestContainer.MYSQL
                .getJdbcUrl()
                .replace("/" + MySqlTestContainer.MYSQL.getDatabaseName(), "/" + DATABASE),
            MySqlTestContainer.MYSQL.getUsername(),
            MySqlTestContainer.MYSQL.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private long insert(long merchantId, String orderId) throws SQLException {
    long id = nextId++;
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO payment (id, payment_key, order_id, merchant_id, wallet_id, method, amount,"
                + " balance_amount, status, created_at)"
                + " VALUES (?, ?, ?, ?, NULL, 'CARD', 10000, 10000, 'READY', NOW(6))")) {
      statement.setLong(1, id);
      statement.setString(2, "pk-rehearsal-" + id);
      statement.setString(3, orderId);
      statement.setLong(4, merchantId);
      statement.executeUpdate();
    }
    return id;
  }

  private String orderIdOf(long id) throws SQLException {
    return query("SELECT order_id FROM payment WHERE id = " + id).get(0).get("order_id").toString();
  }

  private List<Long> ids(String sql) throws SQLException {
    return query(sql).stream().map(row -> ((Number) row.get("id")).longValue()).toList();
  }

  private List<Long> longs(String sql) throws SQLException {
    return query(sql).stream()
        .map(row -> ((Number) row.values().iterator().next()).longValue())
        .toList();
  }

  private List<Map<String, Object>> history() throws SQLException {
    return query(
        "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
  }

  private List<Map<String, Object>> query(String sql) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      int columns = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= columns; i++) {
          row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  /** 여러 문장으로 된 단계를 순서대로 실행한다. */
  private void run(String step) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String sql : step.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  /** 정리 스크립트에서 {@code -- @step name} 부터 다음 단계 전까지의 SQL. 주석 줄은 뺀다. */
  private static String step(String name) {
    try {
      Map<String, StringBuilder> steps = new LinkedHashMap<>();
      StringBuilder current = null;
      for (String line : Files.readAllLines(CLEANUP, StandardCharsets.UTF_8)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("-- @step ")) {
          current = new StringBuilder();
          steps.put(trimmed.substring("-- @step ".length()).trim(), current);
        } else if (current != null && !trimmed.startsWith("--")) {
          current.append(line).append('\n');
        }
      }
      StringBuilder sql = steps.get(name);
      assertThat(sql).as("정리 스크립트의 단계 " + name).isNotNull();
      String text = sql.toString().trim();
      // 단일 SELECT 단계는 끝의 세미콜론을 떼어 executeQuery 에 넘긴다.
      return text.endsWith(";") && text.indexOf(';') == text.length() - 1
          ? text.substring(0, text.length() - 1)
          : text;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Connection root() throws SQLException {
    return DriverManager.getConnection(
        MySqlTestContainer.MYSQL.getJdbcUrl(), "root", MySqlTestContainer.MYSQL.getPassword());
  }

  /** {@link PaymentSchema} 와 같은 조회를 JDBC 연결로. 이 테스트에는 {@code JdbcTemplate} 이 없다. */
  private static final class PaymentSchemaOf {

    static List<String> indexNames(Connection connection) throws SQLException {
      List<String> names = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet rs =
              statement.executeQuery(
                  "SELECT DISTINCT index_name FROM information_schema.statistics"
                      + " WHERE table_schema = DATABASE() AND table_name = 'payment'")) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      return names;
    }

    static String orderIdCollation(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement();
          ResultSet rs =
              statement.executeQuery(
                  "SELECT collation_name FROM information_schema.columns"
                      + " WHERE table_schema = DATABASE() AND table_name = 'payment'"
                      + " AND column_name = 'order_id'")) {
        rs.next();
        return rs.getString(1);
      }
    }
  }
}
