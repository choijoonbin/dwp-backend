package com.dwp.services.main;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * DWP Main Service
 * 
 * - 사용자 정보 및 공통 메타데이터 관리
 * - AI 에이전트 작업(AgentTask) 관리
 */
@Slf4j
@SpringBootApplication
@EnableFeignClients(basePackages = "com.dwp.services.main")
@EnableAsync  // 비동기 작업 활성화 (AI 에이전트 장기 실행 작업용)
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class MainServiceApplication {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    public static void main(String[] args) {
        SpringApplication.run(MainServiceApplication.class, args);
    }
    
    @PostConstruct
    public void checkCoreConfigLoaded() {
        log.info("================================================================================");
        log.info("DWP Main Service Started");
        log.info("Checking Core Configuration...");
        
        // GlobalExceptionHandler 체크
        boolean hasGlobalExceptionHandler = applicationContext.containsBean("globalExceptionHandler");
        log.info("  - GlobalExceptionHandler: {}", hasGlobalExceptionHandler ? "✅ LOADED" : "❌ MISSING (will be fixed in C03-C09)");
        
        // FeignHeaderInterceptor 체크
        boolean hasFeignInterceptor = applicationContext.containsBean("feignHeaderInterceptor");
        log.info("  - FeignHeaderInterceptor: {}", hasFeignInterceptor ? "✅ LOADED" : "❌ MISSING (will be fixed in C03-C09)");
        
        // ObjectMapper 체크
        boolean hasObjectMapper = applicationContext.containsBean("objectMapper");
        log.info("  - ObjectMapper: {}", hasObjectMapper ? "✅ LOADED" : "⚠️ MAY BE MISSING");
        
        // RedisTemplate 체크
        boolean hasRedisTemplate = applicationContext.containsBean("redisTemplate");
        log.info("  - RedisTemplate: {}", hasRedisTemplate ? "✅ LOADED" : "⚠️ NOT REQUIRED or MISSING");
        
        log.info("================================================================================");
    }
}

