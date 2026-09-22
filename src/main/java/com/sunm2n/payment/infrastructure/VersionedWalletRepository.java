package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.VersionedWallet;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * S2-a 전용 지갑 리포지터리.
 *
 * <p>조회는 {@code findById} 하나면 된다. 잔액 변경은 더티체킹에 맡기고, 버전 검사는 flush/commit 시점에 Hibernate 가 {@code
 * UPDATE ... WHERE id=? AND version=?} 로 수행한다.
 */
public interface VersionedWalletRepository extends JpaRepository<VersionedWallet, Long> {}
