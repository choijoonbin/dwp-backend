package com.dwp.services.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 설정
 * 
 * Admin 권한 검증 Interceptor를 등록합니다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    
    private final AdminGuardInterceptor adminGuardInterceptor;
    
    @Override
    @SuppressWarnings("null")
    public void addInterceptors(@org.springframework.lang.NonNull InterceptorRegistry registry) {
        registry.addInterceptor(adminGuardInterceptor)
                .addPathPatterns("/api/admin/**", "/admin/**");
    }
}
