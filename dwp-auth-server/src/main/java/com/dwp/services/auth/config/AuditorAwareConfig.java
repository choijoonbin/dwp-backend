package com.dwp.services.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

/**
 * JPA Auditing용 AuditorAware
 *
 * - Insert: createdBy, updatedBy = 로그인 사용자 ID (세션/ JWT에서 추출)
 * - Update: createdBy 유지, updatedBy = 로그인 사용자 ID
 * - 로그인 사용자 없음(배치/시스템): Optional.empty() → createdBy/updatedBy null
 */
@Configuration
public class AuditorAwareConfig {

    @Bean(name = "auditorProvider")
    public AuditorAware<Long> auditorProvider() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof Jwt)) {
                return Optional.empty();
            }
            String sub = ((Jwt) auth.getPrincipal()).getSubject();
            if (sub == null || sub.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(sub));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        };
    }
}
