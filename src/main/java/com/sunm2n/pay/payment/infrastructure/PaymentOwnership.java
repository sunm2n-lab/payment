package com.sunm2n.pay.payment.infrastructure;

/**
 * 결제의 식별자와 소유 가맹점. 둘 다 생성 후 바뀌지 않는 값이다.
 *
 * <p>S3 의 비관적 락 취소가 {@code FOR UPDATE} <b>앞에</b> 쓰는 조회 결과다. 엔티티가 아니라 스칼라로 받는 이유는 {@link
 * PaymentRepository#findByIdForUpdate} 에 있다.
 */
public record PaymentOwnership(Long id, Long merchantId) {}
