package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phase 0 에서 쓰는 코드는 없다. S10 에서 처음 사용한다. */
public interface SettlementRepository extends JpaRepository<Settlement, Long> {}
