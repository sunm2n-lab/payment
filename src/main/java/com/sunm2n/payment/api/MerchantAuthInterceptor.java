package com.sunm2n.payment.api;

import com.sunm2n.payment.domain.Merchant;
import com.sunm2n.payment.infrastructure.MerchantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code X-API-Key} 헤더로 가맹점을 식별한다.
 *
 * <p>여기서 던진 예외는 DispatcherServlet 의 HandlerExceptionResolver 를 타므로 {@code ApiExceptionHandler} 가
 * 401 로 매핑한다.
 */
@Component
public class MerchantAuthInterceptor implements HandlerInterceptor {

  public static final String API_KEY_HEADER = "X-API-Key";

  private final MerchantRepository merchantRepository;
  private final MerchantContext merchantContext;

  public MerchantAuthInterceptor(
      MerchantRepository merchantRepository, MerchantContext merchantContext) {
    this.merchantRepository = merchantRepository;
    this.merchantContext = merchantContext;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String apiKey = request.getHeader(API_KEY_HEADER);
    if (!StringUtils.hasText(apiKey)) {
      throw new UnauthorizedMerchantException(API_KEY_HEADER + " 헤더가 필요합니다.");
    }

    Merchant merchant =
        merchantRepository
            .findByApiKey(apiKey)
            .orElseThrow(() -> new UnauthorizedMerchantException("등록되지 않은 API 키입니다."));

    merchantContext.set(merchant);
    return true;
  }
}
