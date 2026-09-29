package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * V6 스키마의 통합 테스트 베이스. 등록 방식은 {@link PastSchemaDatabase} 에 있다.
 *
 * <p>V6 의 {@code payment} 에는 {@code merchant_id} 단일 인덱스(비유일)가 있고 {@code order_id} 는 아직 {@code
 * utf8mb4_0900_ai_ci} 다. S6 2 단계(단일 인덱스의 잠금 범위)가 이 전제에 선다. V7 이 인덱스를 복합 unique 로 교체하면 결과가 달라지므로
 * <b>처음부터</b> 여기서 돌린다 ({@code docs/plan/S6.md} 3절).
 */
public abstract class AbstractV6SchemaTest extends AbstractDatabaseTest {

  @DynamicPropertySource
  static void v6Schema(DynamicPropertyRegistry registry) {
    PastSchemaDatabase.register(registry, "payment_v6", "6");
  }
}
