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

@Configuration
public class ProductSurfaceInternalSecurityConfig {

    public static final String TOKEN_HEADER = "X-DWP-Product-Surface-Token";
    public static final String MEETING_FOLLOWUP_TOKEN_HEADER =
            "X-DWP-Meeting-Followup-Authority-Token";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String GATEWAY_SERVICE_IDENTITY = "dwp-gateway";
    static final String MEETING_SERVICE_IDENTITY = "dwp-meeting-server";
    static final String MEETING_FOLLOWUP_PATH =
            "/internal/auth/v1/meeting-followup-authority/evaluate";

    @Bean
    @Order(0)
    SecurityFilterChain productSurfaceInternalSecurityFilterChain(
            HttpSecurity http,
            @Value("${dwp.auth.product-surface-token:}") String productSurfaceToken,
            @Value("${dwp.auth.meeting-followup-authority-token:}")
            String meetingFollowupAuthorityToken,
            ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher(
                        "/internal/auth/v1/product-surface-authority/evaluate",
                        "/internal/auth/v1/governed-route-authority/evaluate",
                        MEETING_FOLLOWUP_PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new ProductSurfaceTokenFilter(
                                productSurfaceToken,
                                meetingFollowupAuthorityToken,
                                objectMapper),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static final class ProductSurfaceTokenFilter extends OncePerRequestFilter {

        private final String expectedToken;
        private final String expectedMeetingFollowupToken;
        private final ObjectMapper objectMapper;

        ProductSurfaceTokenFilter(String expectedToken, ObjectMapper objectMapper) {
            this(expectedToken, "", objectMapper);
        }

        ProductSurfaceTokenFilter(
                String expectedToken,
                String expectedMeetingFollowupToken,
                ObjectMapper objectMapper) {
            this.expectedToken = expectedToken == null ? "" : expectedToken.strip();
            this.expectedMeetingFollowupToken = expectedMeetingFollowupToken == null
                    ? "" : expectedMeetingFollowupToken.strip();
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            String identity = exactHeader(request, SERVICE_IDENTITY_HEADER);
            String productSurfaceToken = exactHeader(request, TOKEN_HEADER);
            String meetingFollowupToken = exactHeader(
                    request, MEETING_FOLLOWUP_TOKEN_HEADER);
            boolean gateway = GATEWAY_SERVICE_IDENTITY.equals(identity)
                    && !MEETING_FOLLOWUP_PATH.equals(request.getRequestURI())
                    && absentHeader(request, MEETING_FOLLOWUP_TOKEN_HEADER)
                    && matches(expectedToken, productSurfaceToken);
            boolean meeting = MEETING_SERVICE_IDENTITY.equals(identity)
                    && "POST".equals(request.getMethod())
                    && MEETING_FOLLOWUP_PATH.equals(request.getRequestURI())
                    && absentHeader(request, TOKEN_HEADER)
                    && matches(expectedMeetingFollowupToken, meetingFollowupToken);
            if (!gateway && !meeting) {
                response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(
                        ErrorCode.UNAUTHORIZED,
                        "Product surface service identity is required."));
                return;
            }
            filterChain.doFilter(request, response);
        }

        private boolean matches(String expected, String actual) {
            return !expected.isBlank() && actual != null && MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8));
        }

        private String exactHeader(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name));
            if (values.size() != 1 || values.getFirst() == null
                    || values.getFirst().isBlank()
                    || !values.getFirst().equals(values.getFirst().strip())) {
                return null;
            }
            return values.getFirst();
        }

        private boolean absentHeader(HttpServletRequest request, String name) {
            return !request.getHeaders(name).hasMoreElements();
        }
    }
}
