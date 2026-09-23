package com.sunm2n.pay.wallet.infrastructure;

import com.sunm2n.pay.wallet.domain.WalletLedger;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {

  List<WalletLedger> findByWalletId(Long walletId);
}
