package com.sunm2n.payment.infrastructure;

import com.sunm2n.payment.domain.WalletLedger;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {

  List<WalletLedger> findByWalletId(Long walletId);
}
