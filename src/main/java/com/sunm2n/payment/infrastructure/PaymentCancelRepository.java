package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.PaymentCancel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

  List<PaymentCancel> findByPaymentId(Long paymentId);
}
