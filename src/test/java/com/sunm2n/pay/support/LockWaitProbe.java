package com.sunm2n.pay.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 테이블의 락 대기자 수를 {@code performance_schema} 에서 폴링한다 (S5).
 *
 * <p>"후속 INSERT 가 대기 중에 선행이 롤백했다" 를 확인하려면, 선행을 풀어 주기 <b>전에</b> 후속이 실제로 락을 기다리고 있어야 한다. 이 확인이 없으면
 * "대기하던 INSERT 가 그대로 성공했다" 가 아니라 "선행이 끝난 뒤에 INSERT 했다" 를 본 것일 수 있다 ({@code docs/plan/S5.md} 6절).
 *
 * <p><b>root 로 조회한다.</b> {@code data_lock_waits}·{@code data_locks} 에는 그 테이블의 SELECT 권한이 필요한데, 테스트
 * 사용자는 자기 데이터베이스 권한만 받는다 ({@link MySqlTestContainer#createDatabase}). 같은 연결 방식을 쓴다.
 *
 * <p>{@code data_lock_waits} 에는 스키마·테이블 컬럼이 없다. 요청 락의 id 로 {@code data_locks} 와 조인해 <b>현재 테스트의 DB 와
 * 테이블</b>로 좁힌다. 같은 컨테이너에 데이터베이스가 여럿이다.
 *
 * <p><b>S6 확장 — 무엇이 얼마나 잠겼는가.</b> 대기자 수만으로는 잠금 범위를 볼 수 없다. {@link #soleHolder} 로 측정 대상 트랜잭션을 하나로
 * 고정하고, {@link #locksOf} 로 그 트랜잭션의 락을 {@code LOCK_TYPE}·{@code INDEX_NAME}·{@code LOCK_MODE}·{@code
 * LOCK_STATUS} 별로 센다. DB·테이블로만 좁히면 후속이 가진 락과 대기 중인 요청까지 섞인다 ({@code docs/plan/S6.md} 6절).
 */
public final class LockWaitProbe {

  /** 게이트 상한과 같다. */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final Duration INTERVAL = Duration.ofMillis(20);

  private static final String WAITERS =
      "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID)"
          + " FROM performance_schema.data_lock_waits w"
          + " JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID"
          + " WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = ?";

  private LockWaitProbe() {}

  private static final String HOLDERS =
      "SELECT DISTINCT ENGINE_TRANSACTION_ID FROM performance_schema.data_locks"
          + " WHERE OBJECT_SCHEMA = ? AND OBJECT_NAME = ?";

  private static final String LOCKS =
      "SELECT LOCK_TYPE, INDEX_NAME, LOCK_MODE, LOCK_STATUS, COUNT(*)"
          + " FROM performance_schema.data_locks"
          + " WHERE OBJECT_SCHEMA = ? AND OBJECT_NAME = ? AND ENGINE_TRANSACTION_ID = ?"
          + " GROUP BY LOCK_TYPE, INDEX_NAME, LOCK_MODE, LOCK_STATUS"
          + " ORDER BY LOCK_TYPE, INDEX_NAME, LOCK_MODE, LOCK_STATUS";

  private static final String LOCK_DATA =
      "SELECT LOCK_DATA FROM performance_schema.data_locks"
          + " WHERE OBJECT_SCHEMA = ? AND OBJECT_NAME = ? AND ENGINE_TRANSACTION_ID = ?"
          + " AND INDEX_NAME = ? AND LOCK_MODE = ? ORDER BY LOCK_DATA";

  /**
   * 테이블에 락을 가진 트랜잭션이 <b>정확히 하나</b>인지 확인하고 그 id 를 돌려준다. 하나가 아니면 측정하지 않고 실패한다.
   *
   * <p>선행이 hold 지점에 도착한 직후, 후속을 보내기 <b>전에</b> 부른다. 이 id 가 이후 집계의 필터다.
   */
  public static long soleHolder(String schema, String table) {
    List<Long> holders = new ArrayList<>();
    try (Connection root = root();
        PreparedStatement statement = root.prepareStatement(HOLDERS)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          holders.add(rs.getLong(1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("락 보유 트랜잭션을 조회하지 못했다", e);
    }
    if (holders.size() != 1) {
      throw new IllegalStateException(schema + "." + table + " 에 락을 가진 트랜잭션이 하나가 아니다: " + holders);
    }
    return holders.get(0);
  }

  /** 트랜잭션 {@code trxId} 가 테이블에 가진 락을 종류·인덱스·모드·상태별로 센다. */
  public static List<LockCount> locksOf(String schema, String table, long trxId) {
    List<LockCount> counts = new ArrayList<>();
    try (Connection root = root();
        PreparedStatement statement = root.prepareStatement(LOCKS)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setLong(3, trxId);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          counts.add(
              new LockCount(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getInt(5)));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("락을 집계하지 못했다", e);
    }
    return counts;
  }

  /**
   * 트랜잭션 {@code trxId} 가 인덱스 {@code index} 에 모드 {@code lockMode} 로 가진 락의 {@code LOCK_DATA}. 갭의 위치를
   * 본다.
   */
  public static List<String> lockDataOf(
      String schema, String table, long trxId, String index, String lockMode) {
    List<String> data = new ArrayList<>();
    try (Connection root = root();
        PreparedStatement statement = root.prepareStatement(LOCK_DATA)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setLong(3, trxId);
      statement.setString(4, index);
      statement.setString(5, lockMode);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          data.add(rs.getString(1));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("락 데이터를 조회하지 못했다", e);
    }
    return data;
  }

  /**
   * 레코드 락 개수. {@code index} 는 {@code PRIMARY} 또는 보조 인덱스 이름, {@code status} 는 {@code GRANTED}/{@code
   * WAITING}.
   */
  public static int recordLocks(List<LockCount> counts, String index, String status) {
    return counts.stream()
        .filter(c -> "RECORD".equals(c.lockType()))
        .filter(c -> index.equals(c.indexName()))
        .filter(c -> status.equals(c.lockStatus()))
        .mapToInt(LockCount::count)
        .sum();
  }

  /** 한 트랜잭션의 락 집계 한 줄. 테이블의 의도 락({@code TABLE}, {@code IX})과 레코드 락({@code RECORD})을 섞지 않는다. */
  public record LockCount(
      String lockType, String indexName, String lockMode, String lockStatus, int count) {

    @Override
    public String toString() {
      return lockType + " " + indexName + " " + lockMode + " " + lockStatus + " x" + count;
    }
  }

  private static Connection root() throws SQLException {
    return DriverManager.getConnection(
        MySqlTestContainer.MYSQL.getJdbcUrl(), "root", MySqlTestContainer.MYSQL.getPassword());
  }

  /** 테이블에 락을 기다리는 트랜잭션이 {@code expected} 개 이상이 될 때까지 기다린다. 상한 안에 안 되면 실패한다. */
  public static void awaitWaiters(String schema, String table, int expected) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    int last = 0;
    try (Connection root = root();
        PreparedStatement statement = root.prepareStatement(WAITERS)) {
      statement.setString(1, schema);
      statement.setString(2, table);
      while (System.nanoTime() < deadline) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          last = rs.getInt(1);
        }
        if (last >= expected) {
          return;
        }
        Thread.sleep(INTERVAL.toMillis());
      }
    } catch (SQLException e) {
      throw new IllegalStateException("락 대기를 조회하지 못했다", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("락 대기 폴링 중 인터럽트", e);
    }
    throw new IllegalStateException(
        schema
            + "."
            + table
            + " 의 락 대기자가 "
            + TIMEOUT.toSeconds()
            + "초 안에 "
            + expected
            + "명이 되지 않았다 (마지막 "
            + last
            + "명)");
  }
}
