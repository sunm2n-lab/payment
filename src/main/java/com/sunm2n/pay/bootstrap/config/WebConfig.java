package com.sunm2n.pay.bootstrap.config;

import com.sunm2n.pay.merchant.api.auth.MerchantAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final MerchantAuthInterceptor merchantAuthInterceptor;

  public WebConfig(MerchantAuthInterceptor merchantAuthInterceptor) {
    this.merchantAuthInterceptor = merchantAuthInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(merchantAuthInterceptor).addPathPatterns("/v1/**");
  }
}
