package com.dwp.services.auth.config;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.observability.api.ApiHistoryAttributes;
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
public class ProductAuthorizationOperationsSecurityConfig {

    public static final String INTERNAL_PATH_ROOT =
            "/internal/auth/v1/product-authorization";
    public static final String INTERNAL_PATH_PREFIX =
            INTERNAL_PATH_ROOT + "/operations";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    public static final String APPROVAL_TOKEN_HEADER =
            "X-DWP-Product-Authorization-Approval-Token";
    public static final String ACTIVATION_TOKEN_HEADER =
            "X-DWP-Product-Authorization-Activation-Token";
    public static final String PROVIDER_SERVICE_IDENTITY = "dwp-provider-server";
    public static final String PLATFORM_SERVICE_IDENTITY = "dwp-platform-server";

    @Bean
    @Order(-2)
    SecurityFilterChain productAuthorizationOperationsSecurityFilterChain(
            HttpSecurity http,
            @Value("${dwp.product-authorization.operations.provider-approval-token:}")
            String providerApprovalToken,
            @Value("${dwp.product-authorization.operations.platform-activation-token:}")
            String platformActivationToken,
            ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher(INTERNAL_PATH_ROOT + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new ProductAuthorizationOperationsTokenFilter(
                                providerApprovalToken,
                                platformActivationToken,
                                objectMapper),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static final class ProductAuthorizationOperationsTokenFilter
            extends OncePerRequestFilter {

        private final byte[] providerApprovalToken;
        private final byte[] platformActivationToken;
        private final boolean providerConfigured;
        private final boolean platformConfigured;
        private final ObjectMapper objectMapper;

        ProductAuthorizationOperationsTokenFilter(
                String providerApprovalToken,
                String platformActivationToken,
                ObjectMapper objectMapper) {
            String provider = canonicalSecret(providerApprovalToken);
            String platform = canonicalSecret(platformActivationToken);
            if (!provider.isBlank() && provider.equals(platform)) {
                throw new IllegalStateException(
                        "Product authorization approval and activation tokens must differ.");
            }
            this.providerApprovalToken = provider.getBytes(StandardCharsets.UTF_8);
            this.platformActivationToken = platform.getBytes(StandardCharsets.UTF_8);
            this.providerConfigured = !provider.isBlank();
            this.platformConfigured = !platform.isBlank();
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            Lane lane = lane(request);
            String identity = singleHeader(request, SERVICE_IDENTITY_HEADER);
            String suppliedToken = singleHeader(request, lane.tokenHeader);
            byte[] actualToken = suppliedToken == null
                    ? new byte[0]
                    : suppliedToken.getBytes(StandardCharsets.UTF_8);
            boolean valid = lane.configured(this)
                    & lane.identity.equals(identity)
                    & absent(request, lane.otherTokenHeader)
                    & MessageDigest.isEqual(lane.token(this), actualToken);
            if (!valid) {
                response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(
                        ErrorCode.UNAUTHORIZED,
                        "Product authorization operations service identity is required."));
                return;
            }
            request.setAttribute(ApiHistoryAttributes.ACTOR_TYPE, "SERVICE");
            request.setAttribute(ApiHistoryAttributes.ACTOR_ID, identity);
            request.setAttribute(ApiHistoryAttributes.AUTH_TYPE, "SERVICE");
            filterChain.doFilter(request, response);
        }

        private Lane lane(HttpServletRequest request) {
            String path = request.getRequestURI();
            if ("GET".equals(request.getMethod())
                    && (path.endsWith("/active")
                    || path.matches(".*/versions/[1-9][0-9]*$"))) {
                return Lane.ACTIVATION;
            }
            if (!"POST".equals(request.getMethod())) return Lane.UNKNOWN;
            if (path.endsWith("/approval")) return Lane.APPROVAL;
            if (path.endsWith("/activation") || path.endsWith("/rollback")) {
                return Lane.ACTIVATION;
            }
            return Lane.UNKNOWN;
        }

        private String singleHeader(HttpServletRequest request, String name) {
            if (name.isBlank()) return null;
            List<String> values = Collections.list(request.getHeaders(name));
            return values.size() == 1 ? values.get(0) : null;
        }

        private boolean absent(HttpServletRequest request, String name) {
            return name.isBlank() || !request.getHeaders(name).hasMoreElements();
        }

        private static String canonicalSecret(String value) {
            return value == null ? "" : value.strip();
        }

        private enum Lane {
            APPROVAL(
                    APPROVAL_TOKEN_HEADER,
                    ACTIVATION_TOKEN_HEADER,
                    PROVIDER_SERVICE_IDENTITY),
            ACTIVATION(
                    ACTIVATION_TOKEN_HEADER,
                    APPROVAL_TOKEN_HEADER,
                    PLATFORM_SERVICE_IDENTITY),
            UNKNOWN("", "", "");

            private final String tokenHeader;
            private final String otherTokenHeader;
            private final String identity;

            Lane(String tokenHeader, String otherTokenHeader, String identity) {
                this.tokenHeader = tokenHeader;
                this.otherTokenHeader = otherTokenHeader;
                this.identity = identity;
            }

            private byte[] token(ProductAuthorizationOperationsTokenFilter filter) {
                return switch (this) {
                    case APPROVAL -> filter.providerApprovalToken;
                    case ACTIVATION -> filter.platformActivationToken;
                    case UNKNOWN -> new byte[0];
                };
            }

            private boolean configured(ProductAuthorizationOperationsTokenFilter filter) {
                return switch (this) {
                    case APPROVAL -> filter.providerConfigured;
                    case ACTIVATION -> filter.platformConfigured;
                    case UNKNOWN -> false;
                };
            }
        }
    }
}
