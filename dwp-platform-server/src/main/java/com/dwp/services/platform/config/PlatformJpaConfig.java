package com.dwp.services.platform.config;

import com.dwp.services.platform.security.RequestActorContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "platformAuditorProvider")
public class PlatformJpaConfig {

    @Bean
    AuditorAware<Long> platformAuditorProvider() {
        return RequestActorContext::current;
    }
}
