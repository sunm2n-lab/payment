package com.sunm2n.pay.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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

  private final JdbcTemplate jdbcTemplate;

  public JdbcIdempotencyKeyStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<IdempotencyRecord> find(
      Long merchantId, String operation, String idempotencyKey) {
    return jdbcTemplate.query(SELECT, ROW_MAPPER, merchantId, operation, idempotencyKey).stream()
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
