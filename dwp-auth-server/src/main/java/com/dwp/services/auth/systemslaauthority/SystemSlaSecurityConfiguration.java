package com.dwp.services.auth.systemslaauthority;

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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SystemSlaSecurityConfiguration {
    @Bean @Order(-6) SecurityFilterChain systemSlaSecurityFilterChain(HttpSecurity http, SystemSlaAuthorityService service,
            ObjectMapper mapper, @Value("${dwp.auth.approval-system-sla.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request -> candidate(request.getRequestURI())).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new PurposeFilter(service, mapper, enabled), AnonymousAuthenticationFilter.class);
        return http.build();
    }
    public static boolean candidate(String path) { return path != null && path.contains("approval-system-sla-authority"); }
    public static final class PurposeFilter extends OncePerRequestFilter {
        private final SystemSlaAuthorityService service;
        private final ObjectMapper mapper;
        private final boolean enabled;
        public PurposeFilter(SystemSlaAuthorityService service, ObjectMapper mapper, boolean enabled) { this.service = service; this.mapper = mapper; this.enabled = enabled; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            if (!"POST".equals(request.getMethod()) || !SystemSlaProtocol.PATH.equals(request.getRequestURI()) || request.getQueryString() != null
                    || request.getHeader("Authorization") != null || request.getHeader("Cookie") != null
                    || request.getCookies() != null && request.getCookies().length > 0 || borrowed(request)
                    || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity")) || !utf8Json(request)) { error(response, ErrorCode.FORBIDDEN); return; }
            String token = single(request, SystemSlaProtocol.HEADER);
            if (token == null || token.length() > SystemSlaProtocol.TRANSPORT_LIMIT || request.getContentLengthLong() > SystemSlaProtocol.BODY_LIMIT) { error(response, ErrorCode.FORBIDDEN); return; }
            try { request.setAttribute(SystemSlaController.PROOF_ATTRIBUTE,
                    service.preverify(request.getInputStream().readNBytes(SystemSlaProtocol.BODY_LIMIT + 1), token)); }
            catch (BaseException invalid) { error(response, invalid.getErrorCode()); return; }
            catch (RuntimeException unavailable) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            chain.doFilter(request, response);
        }
        private static boolean borrowed(HttpServletRequest request) {
            return Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.startsWith("x-dwp-") && !Set.of("x-dwp-service-identity", SystemSlaProtocol.HEADER.toLowerCase(Locale.ROOT)).contains(name));
        }
        private static String single(HttpServletRequest request, String name) { var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null; }
        private static boolean utf8Json(HttpServletRequest request) {
            try { var types = Collections.list(request.getHeaders("Content-Type")); if (types.size() != 1) return false;
                var type = MediaType.parseMediaType(types.getFirst()); return MediaType.APPLICATION_JSON.isCompatibleWith(type)
                        && (type.getCharset() == null || java.nio.charset.StandardCharsets.UTF_8.equals(type.getCharset())); }
            catch (IllegalArgumentException invalid) { return false; }
        }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Exact SYSTEM_SLA source authority is required."));
        }
    }
}
