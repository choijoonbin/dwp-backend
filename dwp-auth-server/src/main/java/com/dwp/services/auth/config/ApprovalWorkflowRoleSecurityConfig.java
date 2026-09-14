package com.dwp.services.auth.config;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
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

@Configuration
public class ApprovalWorkflowRoleSecurityConfig {
    public static final String PATH = "/internal/approval-workflow/role-authority";
    public static final String TOKEN_HEADER = "X-DWP-Approval-Role-Authority-Token";

    @Bean
    @Order(-2)
    SecurityFilterChain approvalWorkflowRoleSecurityChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${dwp.auth.approval-workflow-role-authority-enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request -> candidate(request.getRequestURI()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new WorkloadFilter(mapper, enabled), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static boolean candidate(String path) {
        if (path.equals(PATH) || path.startsWith(PATH + '/')) return true;
        try { return PathPatternParser.defaultInstance.parse(PATH).matches(PathContainer.parsePath(path)); }
        catch (IllegalArgumentException exception) { return path.contains("role-authority"); }
    }

    public static final class WorkloadFilter extends OncePerRequestFilter {
        private final ObjectMapper mapper;
        private final boolean enabled;
        public WorkloadFilter(ObjectMapper mapper, boolean enabled) { this.mapper = mapper; this.enabled = enabled; }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String token = single(request, TOKEN_HEADER);
            var allowed = Set.of(TOKEN_HEADER.toLowerCase(Locale.ROOT), "x-dwp-service-identity");
            boolean borrowed = Collections.list(request.getHeaderNames()).stream().map(header -> header.toLowerCase(Locale.ROOT))
                    .anyMatch(header -> header.equals("authorization") || header.startsWith("x-dwp-") && !allowed.contains(header));
            if (token == null || token.isBlank() || token.length() > 2048 || !token.equals(token.strip())
                    || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity")) || borrowed
                    || request.getCookies() != null && request.getCookies().length > 0) {
                error(response, ErrorCode.UNAUTHORIZED); return;
            }
            if (!"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI()) || request.getQueryString() != null
                    || request.getContentType() == null || !json(request.getContentType())) {
                error(response, ErrorCode.FORBIDDEN); return;
            }
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            // Both signatures and the compact token's whole-body digest precede every authority/role lookup.
            chain.doFilter(request, response);
        }

        private boolean json(String contentType) {
            try { return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType)); }
            catch (IllegalArgumentException exception) { return false; }
        }
        private String single(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null;
        }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Exact dedicated workflow ROLE workload authority is required."));
        }
    }
}
