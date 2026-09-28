package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LockWaitProbe} 가 root 로 조회하는 이유를 고정한다 ({@code docs/plan/S5.md} 6절).
 *
 * <p>테스트 사용자는 자기 데이터베이스 권한만 받는다. {@code performance_schema.data_lock_waits} 조회가 거부되는지 착수 시 확인해 남긴다.
 */
class LockWaitProbeTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("테스트 사용자는 performance_schema.data_lock_waits 를 조회할 수 없다")
  void testUserCannotReadLockWaits() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class))
        .rootCause()
        .hasMessageContaining("SELECT command denied");
  }

  @Test
  @DisplayName("대기자가 이미 충분하면 곧바로 돌아온다")
  void returnsImmediatelyWhenSatisfied() {
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

    assertThatCode(() -> LockWaitProbe.awaitWaiters(database, "idempotency_key", 0))
        .doesNotThrowAnyException();
  }
}
