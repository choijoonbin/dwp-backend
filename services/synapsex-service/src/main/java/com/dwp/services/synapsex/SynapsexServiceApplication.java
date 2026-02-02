package com.dwp.services.synapsex;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

/**
 * DWP SynapseX Service
 *
 * SynapseX 전용 백엔드. DB dwp_aura, 스키마 dwp_aura 사용.
 */
@Slf4j
@SpringBootApplication
public class SynapsexServiceApplication {

    @Autowired
    private ApplicationContext applicationContext;

    public static void main(String[] args) {
        SpringApplication.run(SynapsexServiceApplication.class, args);
    }

    @PostConstruct
    public void logStartup() {
        log.info("================================================================================");
        log.info("DWP SynapseX Service Started");
        log.info("Schema: dwp_aura (DB: dwp_aura)");
        boolean hasGlobalExceptionHandler = applicationContext.containsBean("globalExceptionHandler");
        log.info("  - GlobalExceptionHandler: {}", hasGlobalExceptionHandler ? "✅ LOADED" : "❌ MISSING");
        log.info("================================================================================");
    }
}
