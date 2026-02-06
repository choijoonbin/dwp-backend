package com.dwp.services.synapsex.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Aura Case Tab Feign Client 설정
 * - timeout: application.yml feign.client.config.aura-case-tab
 * - retry: 1회 (period 500ms, maxAttempts 2)
 */
@Configuration
public class AuraClientConfig {

    @Bean
    public Retryer auraCaseTabRetryer() {
        return new Retryer.Default(500, TimeUnit.SECONDS.toMillis(2), 2);
    }
}
