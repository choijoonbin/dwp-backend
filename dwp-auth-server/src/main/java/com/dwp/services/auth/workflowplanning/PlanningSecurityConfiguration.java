package com.dwp.services.auth.workflowplanning;

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
public class PlanningSecurityConfiguration {
    private static final String PREFIX = "/internal/approval-workflow/admin-planning";
    @Bean @Order(-11) SecurityFilterChain planningSecurityFilterChain(HttpSecurity http,
            PlanningAuthorityService service, ObjectMapper mapper,
            @Value("${dwp.auth.approval-workflow-planning.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request -> candidate(request.getRequestURI())).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new PurposeFilter(service, mapper, enabled), AnonymousAuthenticationFilter.class);
        return http.build();
    }
    public static boolean candidate(String uri) {
        if (uri.contains("admin-planning")) return true;
        try { return PathPatternParser.defaultInstance.parse(PREFIX + "/**").matches(PathContainer.parsePath(uri)); }
        catch (IllegalArgumentException error) { return false; }
    }
    public static final class PurposeFilter extends OncePerRequestFilter {
        private final PlanningAuthorityService service;
        private final ObjectMapper mapper;
        private final boolean enabled;
        public PurposeFilter(PlanningAuthorityService service, ObjectMapper mapper, boolean enabled) {
            this.service = service; this.mapper = mapper; this.enabled = enabled;
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            if (!"POST".equals(request.getMethod()) || !PlanningProtocol.PATH.equals(request.getRequestURI())
                    || request.getQueryString() != null || request.getCookies() != null && request.getCookies().length > 0
                    || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity"))
                    || request.getHeader("Authorization") != null || request.getHeader("Cookie") != null
                    || borrowed(request) || !json(request)) { error(response, ErrorCode.FORBIDDEN); return; }
            String transport = single(request, PlanningProtocol.HEADER);
            if (transport == null || transport.length() > PlanningProtocol.TRANSPORT_LIMIT
                    || request.getContentLengthLong() > PlanningProtocol.BODY_LIMIT) { error(response, ErrorCode.FORBIDDEN); return; }
            try {
                byte[] body = request.getInputStream().readNBytes(PlanningProtocol.BODY_LIMIT + 1);
                request.setAttribute(PlanningController.PROOF_ATTRIBUTE, service.preverify(body, transport));
            } catch (BaseException invalid) { error(response, invalid.getErrorCode()); return; }
            catch (RuntimeException unavailable) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            chain.doFilter(request, response);
        }
        private boolean borrowed(HttpServletRequest request) {
            return Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.startsWith("x-dwp-") && !Set.of("x-dwp-service-identity",
                            PlanningProtocol.HEADER.toLowerCase(Locale.ROOT)).contains(name));
        }
        private String single(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null;
        }
        private boolean json(HttpServletRequest request) {
            try {
                var values = Collections.list(request.getHeaders("Content-Type"));
                if(values.size()!=1) return false;
                var type=MediaType.parseMediaType(values.getFirst());
                return "application".equals(type.getType()) && "json".equals(type.getSubtype())
                        && type.getParameters().keySet().stream().allMatch("charset"::equals)
                        && (type.getCharset()==null || java.nio.charset.StandardCharsets.UTF_8.equals(type.getCharset()));
            } catch (IllegalArgumentException error) { return false; }
        }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Exact workflow planning source authority is required."));
        }
    }
}
