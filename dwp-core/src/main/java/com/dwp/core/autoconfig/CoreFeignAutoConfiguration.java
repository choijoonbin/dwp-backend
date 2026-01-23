package com.dwp.core.autoconfig;

import com.dwp.core.config.FeignHeaderInterceptor;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * DWP Core Feign Auto-Configuration
 * 
 * OpenFeign을 사용하는 서비스에서 자동으로 로드되는 설정입니다.
 * 
 * 적용 조건:
 * - @ConditionalOnClass(RequestInterceptor.class): Feign이 classpath에 있을 때만 로드
 * 
 * 제공 빈:
 * - FeignHeaderInterceptor: 표준 헤더 자동 전파
 *   (Authorization, X-Tenant-ID, X-User-ID, X-Agent-ID, X-DWP-Source, X-DWP-Caller-Type)
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class CoreFeignAutoConfiguration {
    
    /**
     * FeignHeaderInterceptor 자동 등록
     * 
     * 모든 FeignClient 호출 시 표준 헤더를 자동으로 전파합니다.
     * 이를 통해 멀티테넌시, 인증, 추적 정보가 downstream 서비스까지 전달됩니다.
     */
    @Bean
    public FeignHeaderInterceptor feignHeaderInterceptor() {
        log.info("✅ DWP Core: FeignHeaderInterceptor registered (Standard headers propagation enabled)");
        return new FeignHeaderInterceptor();
    }
}
