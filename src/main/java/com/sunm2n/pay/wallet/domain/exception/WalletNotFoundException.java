package com.sunm2n.pay.wallet.domain.exception;

import com.sunm2n.pay.common.exception.DomainException;

/** 지갑은 Phase 0 에서 시드로만 생긴다. 지갑 생성 API 는 SCENARIO 의 API 표에 없다. */
public class WalletNotFoundException extends DomainException {

  public WalletNotFoundException(Long memberId) {
    super("지갑을 찾을 수 없습니다. memberId=" + memberId);
  }
}
