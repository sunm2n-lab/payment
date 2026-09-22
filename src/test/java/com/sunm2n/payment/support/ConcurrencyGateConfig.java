package com.sunm2n.payment.support;

import com.sunm2n.payment.infrastructure.PaymentRepository;
import com.sunm2n.payment.infrastructure.WalletRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 리포지터리 조회 직후에 {@link ConcurrencyGate} 를 끼운다.
 *
 * <p>동기화 지점은 프로덕션 코드가 아니라 테스트가 소유한다. 관측을 위해 서비스에 훅을 심으면 실습 대상인 naive 구현이 원래 모습을 잃는다. 그래서 {@link
 * BeanPostProcessor} 가 리포지터리 빈을 JDK 동적 프록시로 감싸고, 대상 메서드가 값을 <b>반환한 직후</b> 게이트를 통과시킨다.
 *
 * <p>{@code @MockitoSpyBean} 을 쓰지 않는 이유: Mockito 의 호출 기록과 스텁 동작은 여러 스레드가 같은 목을 동시에 호출하는 상황을 보장하지
 * 않는다. 동시성 테스트의 동기화 장치 자체가 불확실해지면 관측 결과를 믿을 수 없다.
 *
 * <p>{@code @TestConfiguration} 이라 컴포넌트 스캔에 걸리지 않는다. {@link AbstractIntegrationTest} 가 명시적으로 import
 * 하므로 모든 통합 테스트가 같은 컨텍스트 하나를 계속 공유한다.
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
              gate,
              Map.of("findByPaymentKey", ConcurrencyGate.PAYMENT_READ));
        }
        if (bean instanceof WalletRepository) {
          return gated(
              bean, WalletRepository.class, gate, Map.of("findById", ConcurrencyGate.WALLET_READ));
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
   */
  private static <T> Object gated(
      Object target,
      Class<T> type,
      ObjectProvider<ConcurrencyGate> gate,
      Map<String, String> points) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          Object result;
          try {
            result = method.invoke(target, args);
          } catch (InvocationTargetException e) {
            throw e.getTargetException();
          }
          String point = points.get(method.getName());
          if (point != null) {
            gate.getObject().pass(point);
          }
          return result;
        };
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
