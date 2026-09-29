package com.sunm2n.pay.payment.infrastructure;

import com.sunm2n.pay.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  /**
   * {@code payment_key} unique 인덱스로 조회한다. 가맹점 일치 검사는 이 조회 뒤 메모리에서 한다. S6 의 주문 기반 조회는 이와 달리 {@code
   * merchant_id} 를 SQL 조건에 넣는다.
   */
  Optional<Payment> findByPaymentKey(String paymentKey);

  /**
   * 잠그지 않고 id 와 소유 가맹점만 찾는다. S3 의 비관적 락 취소가 {@link #findByIdForUpdate} <b>앞에</b> 쓰는 조회다.
   *
   * <p>두 값 모두 생성 후 바뀌지 않으므로 잠그기 전에 읽고 검사해도 안전하다. 엔티티를 로드하지 않는 것이 요점이다 — 이유는 {@link
   * #findByIdForUpdate} 에 있다. 결제 상태를 읽는 시점이 아니므로 동기화 지점도 아니다.
   */
  @Query(
      "SELECT new com.sunm2n.pay.payment.infrastructure.PaymentOwnership(p.id, p.merchantId)"
          + " FROM Payment p WHERE p.paymentKey = :paymentKey")
  Optional<PaymentOwnership> findOwnershipByPaymentKey(@Param("paymentKey") String paymentKey);

  /**
   * S3 비관적 락 — 결제 행을 잠그며 읽는다.
   *
   * <pre>SELECT ... FROM payment WHERE id = ? FOR UPDATE</pre>
   *
   * <p>이 조회 <b>앞에</b> 같은 결제를 엔티티로 읽어 두면 안 된다. 영속성 컨텍스트에 이미 있는 엔티티는 이 조회의 결과로 상태가 갱신되지 않으므로, 락은 잡았는데
   * 값은 잠그기 전의 것이 된다 ({@code WalletRepository#findByIdForUpdate} 와 같은 함정).
   *
   * <p>{@code payment_key} 가 아니라 PK 로 잠근다. 없는 키를 {@code payment_key} 로 잠그면 REPEATABLE READ 에서
   * unique 인덱스의 그 키가 들어갈 갭에 갭 락이 걸려, 트랜잭션이 끝날 때까지 그 갭에 들어오는 결제 INSERT 가 막힌다. 존재를 {@link
   * #findOwnershipByPaymentKey} 로 확인한 뒤 PK 등치로 잠그면 레코드 락 하나로 끝난다. 결제를 물리 삭제하는 경로가 없다는 것이 전제다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM Payment p WHERE p.id = :id")
  Optional<Payment> findByIdForUpdate(@Param("id") Long id);

  /**
   * 주문 id 로 잠그지 않고 조회한다 ({@code docs/plan/S6.md} 4.1). 비잠금 읽기라 스냅샷을 읽고 어느 스키마에서도 잠금 대기에 막히지 않는다.
   *
   * <p>V7 이전에는 {@code (merchant_id, order_id)} 가 유일하지 않다. 결과가 둘 이상이면 Spring Data 가 {@code
   * IncorrectResultSizeDataAccessException} 을 던지고 500 이 된다. 하나를 골라 응답하지 않는다.
   */
  Optional<Payment> findByMerchantIdAndOrderId(Long merchantId, String orderId);

  /**
   * S6 의 실습 대상 — 주문 id 로 잠그며 읽는다.
   *
   * <pre>SELECT ... FROM payment WHERE merchant_id = ? AND order_id = ? FOR UPDATE</pre>
   *
   * <p>InnoDB 는 조건에 맞은 행이 아니라 <b>찾느라 스캔한 인덱스 레코드</b>를 잠근다. 잠금 범위는 실행 계획이 정한다 — 인덱스 없음(V5)이면 클러스터
   * 인덱스 전체, {@code merchant_id} 단일 인덱스(V6)면 그 가맹점의 엔트리와 뒤 갭, 복합 unique(V7)면 대상 한 행이다. 이 문장은 세 스키마에서
   * 같다.
   *
   * <p>결과가 둘 이상이면(V7 이전의 중복 주문) {@link #findByMerchantIdAndOrderId} 와 같이 500 이다. 잘못된 결제를 취소하지 않는다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM Payment p WHERE p.merchantId = :merchantId AND p.orderId = :orderId")
  Optional<Payment> findByMerchantIdAndOrderIdForUpdate(
      @Param("merchantId") Long merchantId, @Param("orderId") String orderId);

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
