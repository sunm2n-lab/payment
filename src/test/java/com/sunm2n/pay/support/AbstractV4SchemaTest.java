package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * V4 스키마의 통합 테스트 베이스. 등록 방식은 {@link PastSchemaDatabase} 에 있다.
 *
 * <p>V4 의 {@code idempotency_key} 검색 인덱스는 비유일이다. S5 1·2차 재현(check-then-insert 중복, {@code FOR
 * UPDATE} 갭락 데드락)이 이 전제에 선다. V5 가 인덱스를 unique 로 바꾸면 결과가 달라지므로 <b>처음부터</b> 여기서 돌린다 ({@code
 * docs/plan/S5.md} 3절). S7 이 unique 이전 스키마에서 RC 로 다시 돌릴 때도 이 베이스를 쓴다.
 */
public abstract class AbstractV4SchemaTest extends AbstractDatabaseTest {

  @DynamicPropertySource
  static void v4Schema(DynamicPropertyRegistry registry) {
    PastSchemaDatabase.register(registry, "payment_v4", "4");
  }
}
