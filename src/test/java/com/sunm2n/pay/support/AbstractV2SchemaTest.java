package com.sunm2n.pay.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * V2 스키마의 통합 테스트 베이스. 등록 방식은 {@link PastSchemaDatabase} 에 있다.
 *
 * <p>S4 의 V3 가 {@code wallet_ledger -> wallet} FK 를 더하면 naive·낙관적 전략의 "원장 INSERT -> 지갑 UPDATE" 가 FK
 * 락 승격 데드락을 일으켜, S1·S2 의 Lost Update 재현이 데드락 재현으로 바뀐다 ({@code docs/plan/S4.md} 1.1). V2 를 전제로 쓴 실험은
 * 결과가 같더라도 여기서 돌린다.
 */
public abstract class AbstractV2SchemaTest extends AbstractDatabaseTest {

  @DynamicPropertySource
  static void v2Schema(DynamicPropertyRegistry registry) {
    PastSchemaDatabase.register(registry, "payment_v2", "2");
  }
}
