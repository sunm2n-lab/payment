package com.sunm2n.pay.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 키 저장소의 트랜잭션 계약과 선점 판정 ({@code docs/plan/S5.md} 4.3·4.4, 회귀 14).
 *
 * <p>저장소를 직접 부른다. 트랜잭션 밖의 호출이 SQL 을 보내기 전에 실패하는지는 HTTP 로는 만들 수 없는 상황이다.
 */
class JdbcIdempotencyKeyStoreTest extends AbstractIntegrationTest {

  private static final String OPERATION = "CANCEL";
  private static final String HASH = "0".repeat(64);

  @Autowired private IdempotencyKeyStore store;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;
  private Long merchantId;

  @BeforeEach
  void setUp() {
    transaction = new TransactionTemplate(transactionManager);
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("회귀 14 - 트랜잭션 밖의 claim 은 즉시 실패하고 키 행을 남기지 않는다")
  void claimRequiresTransaction() {
    assertThatThrownBy(() -> store.claim(merchantId, OPERATION, "k", HASH))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("활성 트랜잭션");

    assertThat(keyRowCount()).isZero();
  }

  @Test
  @DisplayName("회귀 14 - 트랜잭션 밖의 complete 는 즉시 실패하고 행을 바꾸지 않는다")
  void completeRequiresTransaction() {
    long id = transaction.execute(status -> store.claim(merchantId, OPERATION, "k", HASH));

    assertThatThrownBy(() -> store.complete(id, 200, "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("활성 트랜잭션");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_key WHERE id = ?", String.class, id))
        .isEqualTo("IN_PROGRESS");
  }

  @Test
  @DisplayName("커밋된 키를 다시 선점하면 uk_idempotency_key 위반으로 판정한다")
  void claimingACommittedKeyIsAConflict() {
    transaction.executeWithoutResult(status -> store.claim(merchantId, OPERATION, "k", HASH));

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> store.claim(merchantId, OPERATION, "k", HASH)))
        .isInstanceOf(IdempotencyKeyClaimedException.class);
    assertThat(keyRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("선점 뒤에는 세션의 락 대기 상한이 원래 값으로 돌아와 있다 - 취소의 payment·wallet 대기에 짧은 상한이 새지 않는다")
  void claimRestoresLockWaitTimeout() {
    int[] observed =
        transaction.execute(
            status -> {
              int before = sessionLockWaitTimeout();
              store.claim(merchantId, OPERATION, "k", HASH);
              return new int[] {before, sessionLockWaitTimeout()};
            });

    assertThat(observed[1]).isEqualTo(observed[0]);
    assertThat(observed[0]).as("MySQL 기본값").isEqualTo(50);
  }

  @Test
  @DisplayName("선점이 1062 로 실패해도 세션의 락 대기 상한은 복원된다")
  void claimRestoresLockWaitTimeoutOnConflict() {
    transaction.executeWithoutResult(status -> store.claim(merchantId, OPERATION, "k", HASH));

    Integer after =
        transaction.execute(
            status -> {
              try {
                store.claim(merchantId, OPERATION, "k", HASH);
              } catch (IdempotencyKeyClaimedException expected) {
                // 복원 여부만 본다
              }
              status.setRollbackOnly();
              return sessionLockWaitTimeout();
            });

    assertThat(after).isEqualTo(50);
  }

  private int sessionLockWaitTimeout() {
    return jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
  }

  private int keyRowCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_key", Integer.class);
  }
}
