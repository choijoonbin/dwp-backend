package com.dwp.services.main.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 설정
 * 
 * HITL 보안 인터셉터를 등록합니다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    
    private final HitlSecurityInterceptor hitlSecurityInterceptor;
    
    @Override
    @SuppressWarnings("null")
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(hitlSecurityInterceptor)
                .addPathPatterns("/aura/hitl/**")
                .excludePathPatterns("/aura/hitl/signals/**");  // 신호 조회는 제외
    }
}
