package com.dwp.services.auth.controller.admin.monitoring;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.time.Instant;

/**
 * WithMockJwt용 SecurityContext 생성.
 * Principal을 Jwt 인스턴스로 설정 (AdminGuardInterceptor 요구사항).
 */
public class WithMockJwtSecurityContextFactory implements WithSecurityContextFactory<WithMockJwt> {

    @Override
    public SecurityContext createSecurityContext(WithMockJwt annotation) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(String.valueOf(annotation.userId()))
                .claim("tenant_id", annotation.tenantId())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();

        Authentication auth = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                jwt,
                java.util.Collections.emptyList()
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        return context;
    }
}
