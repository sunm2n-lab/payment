package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.Wallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

  /** 잠금 없는 일반 조회. Phase 0 은 비관적/낙관적 락을 의도적으로 넣지 않는다 — S2 의 Lost Update 실습 대상이다. */
  Optional<Wallet> findByMemberId(Long memberId);
}
