package com.sunm2n.payment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.payment.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 세션의 격리 수준을 확인한다 (SCENARIO 102행).
 *
 * <p>S7 에서 READ COMMITTED 로 내릴 때 이 테스트가 기준선이 된다.
 */
class IsolationLevelTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("세션 격리 수준은 REPEATABLE-READ 다")
  void sessionIsolationIsRepeatableRead() {
    String isolation = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);

    assertThat(isolation).isEqualTo("REPEATABLE-READ");
  }
}
