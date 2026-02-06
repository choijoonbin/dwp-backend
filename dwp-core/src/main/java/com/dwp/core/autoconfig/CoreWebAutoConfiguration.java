package com.dwp.core.autoconfig;

import com.dwp.core.config.AcceptLanguageLocaleResolver;
import com.dwp.core.exception.GlobalExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * DWP Core Web Auto-Configuration
 * 
 * Spring MVC 기반 Web 서비스에서 자동으로 로드되는 설정입니다.
 * 
 * 적용 조건:
 * - @ConditionalOnWebApplication: Web 애플리케이션일 때만 로드
 * - @ConditionalOnClass(RestControllerAdvice.class): Spring MVC가 classpath에 있을 때만 로드
 * 
 * 제공 빈:
 * - GlobalExceptionHandler: 전역 예외 처리 (@RestControllerAdvice)
 * - LocaleResolver: Accept-Language 기반 다국어(ko/en) 지원
 * - ApiResponse<T> Envelope은 공통 DTO로 제공 (별도 빈 불필요)
 */
@Slf4j
@AutoConfiguration
@AutoConfigureBefore(WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
public class CoreWebAutoConfiguration {
    
    /**
     * GlobalExceptionHandler 자동 등록
     * 
     * 모든 서비스에서 일관된 에러 응답 형식(ApiResponse<T>)을 제공합니다.
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        log.info("✅ DWP Core: GlobalExceptionHandler registered (ApiResponse Envelope enabled)");
        return new GlobalExceptionHandler();
    }

    /**
     * Accept-Language 기반 LocaleResolver 등록
     * 
     * ko/en 지원, 미지정/잘못된 값은 ko fallback
     * @ConditionalOnMissingBean: WebMvcAutoConfiguration의 기본 LocaleResolver와 충돌 방지
     */
    @Bean
    @ConditionalOnMissingBean(LocaleResolver.class)
    public LocaleResolver localeResolver() {
        log.info("✅ DWP Core: AcceptLanguageLocaleResolver registered (i18n ko/en)");
        return new AcceptLanguageLocaleResolver();
    }
}
