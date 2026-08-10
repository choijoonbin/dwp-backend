package com.dwp.services.auth.provisioning;

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

@Configuration
public class ProviderProvisioningSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain providerProvisioningSecurityFilterChain(
            HttpSecurity http,
            @Value("${dwp.provider.provisioning-token:}") String provisioningToken,
            ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher("/internal/provider/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new ProvisioningTokenFilter(provisioningToken, objectMapper),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static final class ProvisioningTokenFilter extends OncePerRequestFilter {

        private static final String TOKEN_HEADER = "X-DWP-Provisioning-Token";

        private final String expectedToken;
        private final ObjectMapper objectMapper;

        private ProvisioningTokenFilter(String expectedToken, ObjectMapper objectMapper) {
            this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String actual = request.getHeader(TOKEN_HEADER);
            if (expectedToken.isBlank() || actual == null || !MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(
                        response.getOutputStream(),
                        ApiResponse.error(ErrorCode.UNAUTHORIZED, "Provider provisioning identity is required."));
                return;
            }
            filterChain.doFilter(request, response);
        }
    }
}
