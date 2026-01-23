package com.dwp.services.auth;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Slf4j
@SpringBootApplication
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.dwp.services.auth.repository")
// @ComponentScan 제거: dwp-core AutoConfiguration으로 자동 적용
// @ComponentScan(basePackages = {"com.dwp.core", "com.dwp.services.auth"})
public class AuthServerApplication {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
    
    @PostConstruct
    public void checkCoreConfigLoaded() {
        log.info("================================================================================");
        log.info("DWP Auth Server Started");
        log.info("Checking Core Configuration...");
        
        boolean hasGlobalExceptionHandler = applicationContext.containsBean("globalExceptionHandler");
        log.info("  - GlobalExceptionHandler: {}", hasGlobalExceptionHandler ? "✅ LOADED" : "❌ MISSING");
        
        boolean hasFeignInterceptor = applicationContext.containsBean("feignHeaderInterceptor");
        log.info("  - FeignHeaderInterceptor: {}", hasFeignInterceptor ? "✅ LOADED" : "❌ MISSING");
        
        boolean hasObjectMapper = applicationContext.containsBean("objectMapper");
        log.info("  - ObjectMapper: {}", hasObjectMapper ? "✅ LOADED" : "⚠️ MAY BE MISSING");
        
        boolean hasRedisTemplate = applicationContext.containsBean("redisTemplate");
        log.info("  - RedisTemplate: {}", hasRedisTemplate ? "✅ LOADED" : "⚠️ NOT REQUIRED or MISSING");
        
        log.info("================================================================================");
    }
}

