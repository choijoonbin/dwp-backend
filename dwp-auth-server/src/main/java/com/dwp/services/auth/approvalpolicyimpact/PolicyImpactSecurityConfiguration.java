package com.dwp.services.auth.approvalpolicyimpact;

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

/** A new purpose boundary; no existing Product Surface or workflow matcher is widened. */
@Configuration
public class PolicyImpactSecurityConfiguration {
    private static final String PREFIX = "/internal/auth/v1/approval-policy-impact-authority";
    @Bean @Order(-4) SecurityFilterChain policyImpactSecurityFilterChain(HttpSecurity http,
            PolicyImpactAuthorityService service, ObjectMapper mapper,
            @Value("${dwp.auth.approval-policy-impact.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request -> candidate(request.getRequestURI())).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new PurposeFilter(service, mapper, enabled), AnonymousAuthenticationFilter.class);
        return http.build();
    }
    public static boolean candidate(String uri) {
        if (uri.contains("approval-policy-impact-authority")) return true;
        try { return PathPatternParser.defaultInstance.parse(PREFIX + "/**").matches(PathContainer.parsePath(uri)); }
        catch (IllegalArgumentException error) { return false; }
    }
    public static final class PurposeFilter extends OncePerRequestFilter {
        private final PolicyImpactAuthorityService service;
        private final ObjectMapper mapper;
        private final boolean enabled;
        public PurposeFilter(PolicyImpactAuthorityService service, ObjectMapper mapper, boolean enabled) {
            this.service = service; this.mapper = mapper; this.enabled = enabled;
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            if (!"POST".equals(request.getMethod()) || !PolicyImpactProtocol.PATH.equals(request.getRequestURI())
                    || request.getQueryString() != null || request.getCookies() != null && request.getCookies().length > 0
                    || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity"))
                    || request.getHeader("Authorization") != null || request.getHeader("Cookie") != null
                    || borrowed(request) || !json(request)) { error(response, ErrorCode.FORBIDDEN); return; }
            String transport = single(request, PolicyImpactProtocol.HEADER);
            if (transport == null || transport.length() > PolicyImpactProtocol.TRANSPORT_LIMIT
                    || request.getContentLengthLong() > PolicyImpactProtocol.BODY_LIMIT) { error(response, ErrorCode.FORBIDDEN); return; }
            try {
                byte[] body = request.getInputStream().readNBytes(PolicyImpactProtocol.BODY_LIMIT + 1);
                request.setAttribute(PolicyImpactController.PROOF_ATTRIBUTE, service.preverify(body, transport));
            } catch (BaseException invalid) { error(response, invalid.getErrorCode()); return; }
            catch (RuntimeException unavailable) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            chain.doFilter(request, response);
        }
        private boolean borrowed(HttpServletRequest request) {
            return Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.startsWith("x-dwp-") && !Set.of("x-dwp-service-identity",
                            PolicyImpactProtocol.HEADER.toLowerCase(Locale.ROOT)).contains(name));
        }
        private String single(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null;
        }
        private boolean json(HttpServletRequest request) {
            try {
                var values = Collections.list(request.getHeaders("Content-Type"));
                return values.size() == 1 && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(values.getFirst()));
            } catch (IllegalArgumentException error) { return false; }
        }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Exact policy impact source authority is required."));
        }
    }
}
