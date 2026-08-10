package com.dwp.services.provider.config;

import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "providerAuditorProvider")
public class ProviderJpaConfig {

    @Bean
    AuditorAware<Long> providerAuditorProvider() {
        return ProviderRequestContext::currentUserId;
    }
}
