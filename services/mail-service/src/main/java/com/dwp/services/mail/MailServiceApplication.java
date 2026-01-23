package com.dwp.services.mail;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ApplicationContext;

@Slf4j
@SpringBootApplication
@EnableFeignClients(basePackages = "com.dwp.services.mail")
public class MailServiceApplication {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    public static void main(String[] args) {
        SpringApplication.run(MailServiceApplication.class, args);
    }
    
    @PostConstruct
    public void checkCoreConfigLoaded() {
        log.info("================================================================================");
        log.info("DWP Mail Service Started");
        log.info("Checking Core Configuration...");
        
        boolean hasGlobalExceptionHandler = applicationContext.containsBean("globalExceptionHandler");
        log.info("  - GlobalExceptionHandler: {}", hasGlobalExceptionHandler ? "✅ LOADED" : "❌ MISSING (will be fixed in C03-C09)");
        
        boolean hasFeignInterceptor = applicationContext.containsBean("feignHeaderInterceptor");
        log.info("  - FeignHeaderInterceptor: {}", hasFeignInterceptor ? "✅ LOADED" : "❌ MISSING (will be fixed in C03-C09)");
        
        boolean hasObjectMapper = applicationContext.containsBean("objectMapper");
        log.info("  - ObjectMapper: {}", hasObjectMapper ? "✅ LOADED" : "⚠️ MAY BE MISSING");
        
        log.info("================================================================================");
    }
}

