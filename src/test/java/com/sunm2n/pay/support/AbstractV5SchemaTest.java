package com.sunm2n.pay.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * V5 스키마의 통합 테스트 베이스. 등록 방식은 {@link PastSchemaDatabase} 에 있다.
 *
 * <p>V5 의 {@code payment} 에는 PK 와 {@code payment_key} unique 뿐이다. S6 의 인덱스 없는 잠금 읽기(0·0'·1 단계)가 이
 * 전제에 선다. V6 이 {@code merchant_id} 인덱스를 더하면 실행 계획과 잠금 범위가 달라지므로 <b>처음부터</b> 여기서 돌린다 ({@code
 * docs/plan/S6.md} 3절). S7 이 인덱스 없는 스키마를 RC 로 다시 돌릴 때도 이 베이스를 쓴다.
 *
 * <p>MockMvc 를 함께 켠다. 0·0' 단계는 주문 중복이 HTTP 응답(500)으로 어떻게 드러나는지 보고, 대기 행렬은 서비스를 직접 부른다. 한 베이스에 두어 V5
 * 컨텍스트가 하나로 유지된다.
 */
@AutoConfigureMockMvc
public abstract class AbstractV5SchemaTest extends AbstractDatabaseTest {

  @DynamicPropertySource
  static void v5Schema(DynamicPropertyRegistry registry) {
    PastSchemaDatabase.register(registry, "payment_v5", "5");
  }
}
