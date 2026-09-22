package com.sunm2n.payment.support;

import com.sunm2n.payment.infrastructure.PaymentRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 리포지터리 조회 직후에 {@link ConcurrencyGate} 를 끼운다.
 *
 * <p>동기화 지점은 프로덕션 코드가 아니라 테스트가 소유한다. 관측을 위해 서비스에 훅을 심으면 실습 대상인 naive 구현이 원래 모습을 잃는다. 그래서 {@link
 * BeanPostProcessor} 가 리포지터리 빈을 JDK 동적 프록시로 감싸고, 대상 메서드의 <b>호출 직전</b> 또는 값을 <b>반환한 직후</b> 게이트를
 * 통과시킨다.
 *
 * <p>지점이 둘인 이유는 잠그며 읽는 조회 때문이다. {@code FOR UPDATE} 조회의 반환 직후에 게이트를 걸면 먼저 락을 잡은 워커가 대기하는 동안 나머지는 락에
 * 막혀 게이트에 도달하지 못한다. 호출 직전이 승인과 충전 두 경로가 wallet 락을 잡기 전에 만나는 유일한 공통 지점이다.
 *
 * <p>{@code @MockitoSpyBean} 을 쓰지 않는 이유는 동기화 지점을 명시적으로 관리하기 위해서다. 어느 메서드의 어느 시점이 지점인지가 {@link
 * ConcurrencyGate#PAYMENT_READ} 같은 이름으로 한곳에 모여 있어야, S2~S10 이 지점을 늘려 갈 때 흩어지지 않는다. (Mockito 자체는 스텁을
 * 미리 걸어 두고 여러 스레드가 호출하는 방식을 지원한다. 안전하지 않은 것은 호출 중에 스텁을 다시 걸거나 검증하는 쪽이다.)
 *
 * <p>{@code @TestConfiguration} 이라 컴포넌트 스캔에 걸리지 않고 {@link AbstractIntegrationTest} 가 명시적으로 import
 * 한다. 빈 재정의는 컨텍스트 캐시 키의 일부라 어느 테스트가 무엇을 재정의했느냐에 따라 컨텍스트가 갈리는데, 이 설정은 모든 통합 테스트가 공통으로 얹고 무장 전에는
 * no-op 이므로 그런 갈래를 만들지 않는다.
 */
@TestConfiguration
public class ConcurrencyGateConfig {

  @Bean
  ConcurrencyGate concurrencyGate() {
    return new ConcurrencyGate();
  }

  @Bean
  static BeanPostProcessor repositoryGatePostProcessor(ObjectProvider<ConcurrencyGate> gate) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof PaymentRepository) {
          return gated(
              bean,
              PaymentRepository.class,
              gate::getObject,
              Map.of(),
              Map.of("findByPaymentKey", ConcurrencyGate.PAYMENT_READ));
        }
        if (bean instanceof WalletRepository) {
          return gated(
              bean,
              WalletRepository.class,
              gate::getObject,
              // 잠그며 읽는 조회는 호출 직전에만 걸 수 있다. 반환 직후는 영영 모이지 않는다.
              Map.of("findByIdForUpdate", ConcurrencyGate.WALLET_LOCK_ATTEMPT),
              // findByMemberId 는 naive 충전이 잔액을 읽는 경로다. 결제 생성(MONEY)도 이 지점을 지난다.
              Map.of(
                  "findById",
                  ConcurrencyGate.WALLET_READ,
                  "findByMemberId",
                  ConcurrencyGate.WALLET_READ));
        }
        return bean;
      }
    };
  }

  /**
   * 리포지터리 인터페이스만 노출하는 프록시로 감싼다.
   *
   * <p>대상 빈은 이미 Spring Data 와 트랜잭션 프록시를 거친 것이고, 이 프록시는 그 바깥에 한 겹 더 씌운다. 주입 지점이 요구하는 타입은 리포지터리
   * 인터페이스뿐이므로 AOP 내부 인터페이스까지 흉내 내지 않는다.
   *
   * @param before 호출 <b>직전</b>에 통과시킬 지점 (메서드 이름 -> 게이트 이름)
   * @param after 값을 <b>반환한 직후</b>에 통과시킬 지점
   */
  static <T> Object gated(
      Object target,
      Class<T> type,
      Supplier<ConcurrencyGate> gate,
      Map<String, String> before,
      Map<String, String> after) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          String beforePoint = before.get(method.getName());
          if (beforePoint != null) {
            gate.get().pass(beforePoint);
          }
          Object result;
          try {
            result = method.invoke(target, args);
          } catch (InvocationTargetException e) {
            throw e.getTargetException();
          }
          String afterPoint = after.get(method.getName());
          if (afterPoint != null) {
            gate.get().pass(afterPoint);
          }
          return result;
        };
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
