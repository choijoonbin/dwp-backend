package com.dwp.core.config;

import feign.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign Client 공통 설정
 */
@Configuration
public class FeignConfig {
    
    /**
     * Feign 로깅 레벨 설정
     * FULL: 요청/응답의 헤더, 바디, 메타데이터 모두 로깅
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }
}
