package com.sunm2n.pay.api;

import com.sunm2n.pay.api.dto.ChargeWalletRequest;
import com.sunm2n.pay.api.dto.WalletResponse;
import com.sunm2n.pay.application.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/wallets")
public class WalletController {

  private final WalletService walletService;

  public WalletController(WalletService walletService) {
    this.walletService = walletService;
  }

  @PostMapping("/{memberId}/charge")
  public WalletResponse charge(
      @PathVariable Long memberId, @Valid @RequestBody ChargeWalletRequest request) {
    return WalletResponse.from(walletService.charge(memberId, request.amount()));
  }

  @GetMapping("/{memberId}")
  public WalletResponse get(@PathVariable Long memberId) {
    return WalletResponse.from(walletService.get(memberId));
  }
}
