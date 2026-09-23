package com.sunm2n.pay.payment.infrastructure;

import com.sunm2n.pay.payment.domain.PaymentCancel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

  List<PaymentCancel> findByPaymentId(Long paymentId);
}
