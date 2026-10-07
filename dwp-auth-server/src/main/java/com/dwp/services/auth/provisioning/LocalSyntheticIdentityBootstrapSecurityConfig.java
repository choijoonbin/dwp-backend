package com.dwp.services.auth.provisioning;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/** Fail-closed transport boundary for disposable localhost identity activation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.synthetic-identity-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticIdentityBootstrapSecurityConfig {

    static final String TOKEN_HEADER = "X-DWP-Synthetic-Bootstrap-Token";

    @Bean
    @Order(0)
    SecurityFilterChain localSyntheticIdentityBootstrapSecurityFilterChain(
            HttpSecurity http,
            @Value("${DWP_ENVIRONMENT:}") String environment,
            @Value("${dwp.synthetic-identity-bootstrap.token:}") String token,
            ObjectMapper objectMapper) throws Exception {
        requireLocalConfiguration(environment, token);
        http
                .securityMatcher(request -> "POST".equals(request.getMethod())
                        && LocalSyntheticTokenFilter.PATH.equals(request.getRequestURI()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new LocalSyntheticTokenFilter(token, objectMapper),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static void requireLocalConfiguration(String environment, String token) {
        if (!"local".equals(environment)) {
            throw new IllegalStateException(
                    "Synthetic identity bootstrap is available only in the local environment.");
        }
        if (token == null || token.length() < 32 || !token.equals(token.trim())) {
            throw new IllegalStateException(
                    "Synthetic identity bootstrap requires an unpadded token of at least 32 characters.");
        }
    }

    static boolean loopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }

    static final class LocalSyntheticTokenFilter extends OncePerRequestFilter {

        static final String PATH = "/internal/synthetic/v1/identity/activate";
        private final byte[] expectedToken;
        private final ObjectMapper objectMapper;

        LocalSyntheticTokenFilter(String token, ObjectMapper objectMapper) {
            this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            var tokenHeaders = Collections.list(request.getHeaders(TOKEN_HEADER));
            String actual = tokenHeaders.size() == 1 ? tokenHeaders.getFirst() : null;
            if (!"POST".equals(request.getMethod())
                    || !PATH.equals(request.getRequestURI())
                    || !loopback(request.getRemoteAddr())
                    || actual == null
                    || !actual.equals(actual.trim())
                    || !MessageDigest.isEqual(
                            expectedToken, actual.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(
                        response.getOutputStream(),
                        ApiResponse.error(
                                ErrorCode.UNAUTHORIZED,
                                "Local synthetic bootstrap identity is required."));
                return;
            }
            filterChain.doFilter(request, response);
        }
    }
}
