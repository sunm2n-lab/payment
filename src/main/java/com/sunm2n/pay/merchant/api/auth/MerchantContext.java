package com.sunm2n.pay.merchant.api.auth;

import com.sunm2n.pay.merchant.domain.Merchant;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * 요청 스코프의 가맹점 컨텍스트. {@code MerchantAuthInterceptor} 가 채우고 컨트롤러가 읽는다.
 *
 * <p>서비스 계층은 이 빈을 읽지 않는다. 가맹점 식별자는 컨트롤러가 꺼내 파라미터로 넘긴다.
 */
@Component
@RequestScope
public class MerchantContext {

  private Merchant merchant;

  public void set(Merchant merchant) {
    this.merchant = merchant;
  }

  public Merchant get() {
    if (merchant == null) {
      throw new IllegalStateException("가맹점 인증이 끝나지 않은 요청입니다.");
    }
    return merchant;
  }

  public Long merchantId() {
    return get().getId();
  }
}
