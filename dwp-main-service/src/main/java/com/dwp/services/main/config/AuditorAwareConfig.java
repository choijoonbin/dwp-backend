package com.dwp.services.main.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

/**
 * JPA Auditing용 AuditorAware (Main 서비스)
 *
 * - Insert: createdBy, updatedBy = 로그인 사용자 ID
 * - Update: updatedBy = 로그인 사용자 ID
 * - 현재: 인증 컨텍스트 미연동으로 Optional.empty() 반환 → createdBy/updatedBy null
 *
 * TODO: JWT/인증 필터 연동 시 (예: SecurityContext, Request-scoped userId 등)
 *       현재 사용자 ID를 Long으로 반환하도록 수정.
 *
 * @see docs/essentials/SYSTEM_COLUMNS_POLICY.md
 */
@Configuration
public class AuditorAwareConfig {

    @Bean(name = "auditorProvider")
    public AuditorAware<Long> auditorProvider() {
        return () -> {
            // Main 서비스는 아직 SecurityContext/인증 컨텍스트에 사용자 ID가 설정되지 않음.
            // JWT 기반 인증 연동 시 JwtTokenValidator.extractUserId 또는
            // SecurityContext에서 subject를 Long으로 파싱하여 반환하도록 변경.
            return Optional.empty();
        };
    }
}
