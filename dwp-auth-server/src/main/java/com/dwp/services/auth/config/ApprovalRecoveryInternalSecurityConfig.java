package com.dwp.services.auth.config;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

@Configuration
public class ApprovalRecoveryInternalSecurityConfig {

    public static final String INTERNAL_PATH =
            "/internal/auth/v1/approval-recovery-auditor/resolve";
    public static final String TOKEN_HEADER = "X-DWP-Approval-Recovery-Token";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    public static final String APPROVAL_SERVICE_IDENTITY = "dwp-approval-server";

    @Bean
    @Order(-1)
    SecurityFilterChain approvalRecoveryInternalSecurityFilterChain(
            HttpSecurity http,
            @Value("${dwp.auth.approval-recovery-token:}") String approvalRecoveryToken,
            ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher(INTERNAL_PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new ApprovalRecoveryTokenFilter(
                                approvalRecoveryToken,
                                objectMapper),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static final class ApprovalRecoveryTokenFilter extends OncePerRequestFilter {

        private final byte[] expectedToken;
        private final boolean configured;
        private final ObjectMapper objectMapper;

        ApprovalRecoveryTokenFilter(String expectedToken, ObjectMapper objectMapper) {
            String canonical = expectedToken == null ? "" : expectedToken.strip();
            this.expectedToken = canonical.getBytes(StandardCharsets.UTF_8);
            this.configured = !canonical.isBlank();
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String suppliedToken = singleHeader(request, TOKEN_HEADER);
            byte[] actualToken = suppliedToken == null
                    ? new byte[0]
                    : suppliedToken.getBytes(StandardCharsets.UTF_8);
            boolean validToken = configured
                    & MessageDigest.isEqual(expectedToken, actualToken);
            boolean validIdentity = APPROVAL_SERVICE_IDENTITY.equals(
                    singleHeader(request, SERVICE_IDENTITY_HEADER));
            if (!validToken || !validIdentity) {
                response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(
                        ErrorCode.UNAUTHORIZED,
                        "Approval recovery service identity is required."));
                return;
            }
            filterChain.doFilter(request, response);
        }

        private String singleHeader(HttpServletRequest request, String name) {
            List<String> values = Collections.list(request.getHeaders(name));
            return values.size() == 1 ? values.get(0) : null;
        }
    }
}
