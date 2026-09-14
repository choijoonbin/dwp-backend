package com.dwp.services.auth.approvalsignatures;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration(proxyBeanMethods = false)
public class SignatureAuthoritySecurityConfiguration {
    @Bean @Order(-6) SecurityFilterChain signatureAuthoritySecurityChain(HttpSecurity http, SignatureAuthorityService service,
            ObjectMapper mapper, @Value("${dwp.auth.approval-signature-source.enabled:false}") boolean enabled) throws Exception {
        return http.securityMatcher(request -> candidate(request.getRequestURI())).csrf(value -> value.disable())
                .sessionManagement(value -> value.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(value -> value.anyRequest().permitAll())
                .addFilterBefore(new PurposeFilter(service, mapper, enabled), AnonymousAuthenticationFilter.class).build();
    }
    public static boolean candidate(String uri) {
        if (uri != null && uri.contains("approval-signature-authority")) return true;
        try { return PathPatternParser.defaultInstance.parse("/internal/auth/v1/approval-signature-authority/**").matches(PathContainer.parsePath(uri)); }
        catch (IllegalArgumentException malformed) { return false; }
    }
    public static final class PurposeFilter extends OncePerRequestFilter {
        private final SignatureAuthorityService service;
        private final ObjectMapper mapper;
        private final boolean enabled;
        public PurposeFilter(SignatureAuthorityService service, ObjectMapper mapper, boolean enabled) {
            this.service = service; this.mapper = mapper; this.enabled = enabled;
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            if (!"POST".equals(request.getMethod()) || !SignatureAuthorityProtocol.PATH.equals(request.getRequestURI())
                    || request.getQueryString() != null || request.getHeader("Authorization") != null || request.getHeader("Cookie") != null
                    || request.getCookies() != null && request.getCookies().length > 0
                    || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity")) || borrowed(request) || !json(request)) {
                error(response, ErrorCode.FORBIDDEN); return;
            }
            String token = single(request, SignatureAuthorityProtocol.HEADER);
            if (token == null || token.length() > SignatureAuthorityProtocol.TOKEN_LIMIT || request.getContentLengthLong() > SignatureAuthorityProtocol.BODY_LIMIT) {
                error(response, ErrorCode.FORBIDDEN); return;
            }
            try {
                byte[] body = request.getInputStream().readNBytes(SignatureAuthorityProtocol.BODY_LIMIT + 1);
                request.setAttribute(SignatureAuthorityController.PROOF, service.preverify(body, token));
            } catch (BaseException invalid) { error(response, invalid.getErrorCode()); return; }
            catch (RuntimeException invalid) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            chain.doFilter(request, response);
        }
        private boolean borrowed(HttpServletRequest request) {
            return Collections.list(request.getHeaderNames()).stream().map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(value -> value.startsWith("x-dwp-") && !Set.of("x-dwp-service-identity", SignatureAuthorityProtocol.HEADER.toLowerCase(Locale.ROOT)).contains(value));
        }
        private static String single(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null;
        }
        private static boolean json(HttpServletRequest request) {
            var values = Collections.list(request.getHeaders("Content-Type"));
            try { return values.size() == 1 && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(values.getFirst())); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Native signature source authority is required."));
        }
    }
}
