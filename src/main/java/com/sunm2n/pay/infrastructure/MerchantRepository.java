package com.sunm2n.pay.infrastructure;

import com.sunm2n.pay.domain.Merchant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

  Optional<Merchant> findByApiKey(String apiKey);
}
