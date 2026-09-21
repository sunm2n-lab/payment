package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  /**
   * {@code payment_key} unique 인덱스로 조회한다. 가맹점 일치 검사는 이 조회 뒤 메모리에서 하므로 S6 의 {@code
   * merchant_id}/{@code order_id} 인덱스 부재 실험에는 영향이 없다.
   */
  Optional<Payment> findByPaymentKey(String paymentKey);
}
