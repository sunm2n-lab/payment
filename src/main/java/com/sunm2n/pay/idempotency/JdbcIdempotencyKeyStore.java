package com.sunm2n.pay.idempotency;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link IdempotencyKeyStore} 의 JDBC 구현.
 *
 * <p>{@code JdbcTemplate} 은 활성 트랜잭션이 있으면 그 트랜잭션의 커넥션을 쓴다. {@code JpaTransactionManager} 도 자기 커넥션을
 * 노출하므로, 키 INSERT 와 JPA 변경이 같은 트랜잭션이 된다.
 *
 * <p>트랜잭션 요구는 {@code @Transactional(MANDATORY)} 가 아니라 {@link
 * TransactionSynchronizationManager#isActualTransactionActive()} 로 명시적으로 검사한다. 애너테이션은 프록시가 있어야만
 * 동작하고, 저장소가 던진 예외가 트랜잭션에 rollback-only 표시를 남긴다 ({@code docs/plan/S5.md} 4.3 조건 5).
 *
 * <p><b>커넥션 일치와 트랜잭션 원자성은 다른 조건이다.</b> {@link #claim} 의 세션 변수 조회·변경·INSERT·복원은 활성 트랜잭션에 묶인 한 커넥션에서
 * 나간다. {@code ConnectionCallback} 으로 한 커넥션에 묶기만 해서는 자동 커밋을 막지 못한다. 그래서 트랜잭션을 강제한다.
 */
@Component
public class JdbcIdempotencyKeyStore implements IdempotencyKeyStore {

  private static final RowMapper<IdempotencyRecord> ROW_MAPPER =
      (rs, rowNum) ->
          new IdempotencyRecord(
              rs.getLong("id"),
              rs.getString("request_hash"),
              IdempotencyStatus.valueOf(rs.getString("status")),
              rs.getObject("response_status", Integer.class),
              rs.getString("response_body"));

  private static final String SELECT =
      "SELECT id, request_hash, status, response_status, response_body FROM idempotency_key"
          + " WHERE merchant_id = ? AND operation = ? AND idempotency_key = ?";

  /** 선점 대상 unique 인덱스 (V5). 이 인덱스의 1062 만 선점 충돌이다. */
  static final String UNIQUE_INDEX = "uk_idempotency_key";

  private static final String CLAIM =
      "INSERT INTO idempotency_key (merchant_id, operation, idempotency_key, request_hash,"
          + " status, created_at) VALUES (?, ?, ?, ?, ?, NOW(6))";

  private final JdbcTemplate jdbcTemplate;
  private final int claimLockWaitTimeoutSeconds;

  public JdbcIdempotencyKeyStore(
      JdbcTemplate jdbcTemplate,
      @Value("${payment.idempotency.claim-lock-wait-timeout-seconds:3}")
          int claimLockWaitTimeoutSeconds) {
    this.jdbcTemplate = jdbcTemplate;
    this.claimLockWaitTimeoutSeconds = claimLockWaitTimeoutSeconds;
  }

  /**
   * 선점 INSERT 한 문장에만 짧은 락 대기 상한을 둔다. INSERT 직후, 취소가 시작되기 전에 원래 값으로 되돌린다.
   *
   * <p>취소 전체가 끝난 뒤에 되돌리면 payment·wallet 잠금 대기에도 짧은 상한이 적용된다. 그 1205 까지 {@code
   * IDEMPOTENCY_KEY_IN_USE} 로 번역하면 실제 원인을 잘못 설명한다. 세션 변수는 HikariCP 가 반환 시 되돌리지 않으므로 복원은 {@code
   * finally} 에 둔다.
   */
  @Override
  public long claim(Long merchantId, String operation, String idempotencyKey, String requestHash) {
    requireActiveTransaction("claim");
    Integer original =
        jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = " + claimLockWaitTimeoutSeconds);
    try {
      KeyHolder keyHolder = new GeneratedKeyHolder();
      jdbcTemplate.update(
          connection -> {
            PreparedStatement statement =
                connection.prepareStatement(CLAIM, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, merchantId);
            statement.setString(2, operation);
            statement.setString(3, idempotencyKey);
            statement.setString(4, requestHash);
            statement.setString(5, IdempotencyStatus.IN_PROGRESS.name());
            return statement;
          },
          keyHolder);
      return Objects.requireNonNull(keyHolder.getKey()).longValue();
    } catch (DataAccessException e) {
      if (MySqlLockErrors.isDuplicateKey(e, UNIQUE_INDEX)) {
        throw new IdempotencyKeyClaimedException(idempotencyKey, e);
      }
      if (MySqlLockErrors.isLockWaitTimeout(e)) {
        throw new IdempotencyKeyInUseException(idempotencyKey, e);
      }
      throw e;
    } finally {
      jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = " + original);
    }
  }

  @Override
  public void complete(long id, int responseStatus, String responseBody) {
    requireActiveTransaction("complete");
    int updated =
        jdbcTemplate.update(
            "UPDATE idempotency_key SET status = ?, response_status = ?, response_body = ?,"
                + " completed_at = NOW(6) WHERE id = ? AND status = ?",
            IdempotencyStatus.COMPLETED.name(),
            responseStatus,
            responseBody,
            id,
            IdempotencyStatus.IN_PROGRESS.name());
    if (updated != 1) {
      throw new IllegalStateException("선점한 키 행을 완료하지 못했다. 같은 트랜잭션에서 claim 한 id 여야 한다. id=" + id);
    }
  }

  @Override
  public Optional<IdempotencyRecord> find(
      Long merchantId, String operation, String idempotencyKey) {
    return jdbcTemplate.query(SELECT, ROW_MAPPER, merchantId, operation, idempotencyKey).stream()
        .findFirst();
  }

  @Override
  public Optional<IdempotencyRecord> findForUpdate(
      Long merchantId, String operation, String idempotencyKey) {
    requireActiveTransaction("findForUpdate");
    return jdbcTemplate
        .query(SELECT + " FOR UPDATE", ROW_MAPPER, merchantId, operation, idempotencyKey)
        .stream()
        .findFirst();
  }

  @Override
  public void record(Long merchantId, String operation, String idempotencyKey, String requestHash) {
    requireActiveTransaction("record");
    jdbcTemplate.update(
        "INSERT INTO idempotency_key (merchant_id, operation, idempotency_key, request_hash,"
            + " status, created_at, completed_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))",
        merchantId,
        operation,
        idempotencyKey,
        requestHash,
        IdempotencyStatus.COMPLETED.name());
  }

  /** SQL 을 보내기 전에 실패한다. 트랜잭션 밖의 키 쓰기는 자동 커밋되어 업무 실패 뒤에도 남는다. */
  private static void requireActiveTransaction(String operation) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "멱등키 " + operation + " 는 활성 트랜잭션 안에서만 호출할 수 있다. 트랜잭션 밖에서는 키 쓰기가 자동 커밋된다");
    }
  }
}
