package com.dwp.services.auth.informationreplay;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
public class InformationReplaySecurityConfig {
    @Bean @Order(-4)
    SecurityFilterChain informationReplaySecurityChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${dwp.auth.approval-information-replay.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request -> candidate(request.getRequestURI())).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new WorkloadFilter(mapper, enabled), AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static boolean candidate(String path) {
        if (path.equals(InformationReplayProtocol.PATH) || path.startsWith(InformationReplayProtocol.PATH + '/')) return true;
        try { return PathPatternParser.defaultInstance.parse(InformationReplayProtocol.PATH).matches(PathContainer.parsePath(path)); }
        catch (IllegalArgumentException invalid) { return path.contains("information-command-replay"); }
    }
    public static final class WorkloadFilter extends OncePerRequestFilter {
        private final ObjectMapper mapper;
        private final boolean enabled;
        public WorkloadFilter(ObjectMapper mapper, boolean enabled) { this.mapper = mapper; this.enabled = enabled; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var allowed = Set.of(InformationReplayProtocol.HEADER.toLowerCase(Locale.ROOT), "x-dwp-service-identity");
            boolean borrowed = Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.equals("authorization") || name.equals("cookie") || name.startsWith("x-dwp-") && !allowed.contains(name));
            String token = single(request, InformationReplayProtocol.HEADER);
            if (borrowed || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity")) || token == null
                    || token.isBlank() || !token.equals(token.strip()) || token.length() > InformationReplayProtocol.TRANSPORT_LIMIT) {
                error(response, ErrorCode.UNAUTHORIZED); return;
            }
            if (!InformationReplayProtocol.PATH.equals(request.getRequestURI()) || !"POST".equals(request.getMethod())
                    || request.getQueryString() != null || !json(request.getContentType())) { error(response, ErrorCode.FORBIDDEN); return; }
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            byte[] body = request.getInputStream().readNBytes(InformationReplayProtocol.BODY_LIMIT + 1);
            if (body.length == 0 || body.length > InformationReplayProtocol.BODY_LIMIT) { error(response, ErrorCode.FORBIDDEN); return; }
            chain.doFilter(new BodyRequest(request, body), response);
        }
        private static String single(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null;
        }
        private static boolean json(String raw) {
            if (raw == null) return false;
            try {
                var type = MediaType.parseMediaType(raw);
                return "application".equals(type.getType()) && "json".equals(type.getSubtype())
                        && (type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset()));
            } catch (IllegalArgumentException invalid) { return false; }
        }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Dedicated information replay evidence is required."));
        }
    }
    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body.clone(); }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous replay body only."); }
            };
        }
    }
}
