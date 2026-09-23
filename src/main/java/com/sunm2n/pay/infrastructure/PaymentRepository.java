package com.sunm2n.pay.infrastructure;

import com.sunm2n.pay.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  /**
   * {@code payment_key} unique 인덱스로 조회한다. 가맹점 일치 검사는 이 조회 뒤 메모리에서 하므로 S6 의 {@code
   * merchant_id}/{@code order_id} 인덱스 부재 실험에는 영향이 없다.
   */
  Optional<Payment> findByPaymentKey(String paymentKey);

  /**
   * S1 의 조건부 UPDATE — READY 인 결제만 IN_PROGRESS 로 바꾸고 갱신 건수를 돌려준다. 1 을 받은 요청만 승인을 진행한다.
   *
   * <p>네이티브 SQL 로 적은 이유는 이 문장 자체가 실습 대상이기 때문이다. 어떤 SQL 이 나가는지가 JPQL 번역에 가려지면 안 된다. {@code status} 는
   * {@code VARCHAR} 이고 엔티티도 {@code @Enumerated(STRING)} 이므로 문자열 리터럴과 표현이 일치한다.
   *
   * <p>갱신 건수는 matched 든 changed 든 같다. WHERE 가 {@code READY} 이고 SET 이 {@code IN_PROGRESS} 라 조건에 걸린
   * 행은 반드시 값이 바뀌기 때문에, 드라이버의 {@code useAffectedRows} 설정에 결과가 좌우되지 않는다.
   *
   * <p>{@code clearAutomatically} 로 영속성 컨텍스트를 비운다. 이 UPDATE 는 영속성 컨텍스트를 거치지 않으므로, 비우지 않으면 이미 로드해 둔
   * 엔티티가 옛 상태를 그대로 들고 있게 된다. {@code flushAutomatically} 는 반대로 아직 반영되지 않은 변경이 이 UPDATE 뒤로 밀려 순서가
   * 뒤집히는 것을 막는다.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE payment SET status = 'IN_PROGRESS'"
              + " WHERE payment_key = :paymentKey AND status = 'READY'",
      nativeQuery = true)
  int markInProgress(@Param("paymentKey") String paymentKey);
}
