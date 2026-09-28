package com.sunm2n.pay.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

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
 * 테이블</b>로 좁힌다. 같은 컨테이너에 데이터베이스가 셋이다.
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

  /** 테이블에 락을 기다리는 트랜잭션이 {@code expected} 개 이상이 될 때까지 기다린다. 상한 안에 안 되면 실패한다. */
  public static void awaitWaiters(String schema, String table, int expected) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    int last = 0;
    try (Connection root =
            DriverManager.getConnection(
                MySqlTestContainer.MYSQL.getJdbcUrl(),
                "root",
                MySqlTestContainer.MYSQL.getPassword());
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
