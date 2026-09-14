package com.dwp.services.auth.workflowruntime;

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

@Configuration
public class WorkflowRuntimeSecurityConfig {
    @Bean @Order(-3)
    SecurityFilterChain workflowRuntimeSecurityChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${dwp.auth.approval-workflow-runtime.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request -> candidate(request.getRequestURI())).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new WorkloadFilter(mapper, enabled), AnonymousAuthenticationFilter.class);
        return http.build();
    }
    static boolean candidate(String path) {
        if (path.equals(WorkflowRuntimeProtocol.PATH) || path.startsWith(WorkflowRuntimeProtocol.PATH + '/')) return true;
        try { return PathPatternParser.defaultInstance.parse(WorkflowRuntimeProtocol.PATH).matches(PathContainer.parsePath(path)); }
        catch (IllegalArgumentException exception) { return path.contains("runtime-authority"); }
    }
    public static final class WorkloadFilter extends OncePerRequestFilter {
        private final ObjectMapper mapper;
        private final boolean enabled;
        public WorkloadFilter(ObjectMapper mapper, boolean enabled) { this.mapper = mapper; this.enabled = enabled; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            var allowed = Set.of(WorkflowRuntimeProtocol.TOKEN_HEADER.toLowerCase(Locale.ROOT), "x-dwp-service-identity");
            boolean borrowed = Collections.list(request.getHeaderNames()).stream().map(name -> name.toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.equals("authorization") || name.startsWith("x-dwp-") && !allowed.contains(name));
            String token = single(request, WorkflowRuntimeProtocol.TOKEN_HEADER);
            if (token == null || token.isBlank() || token.length() > 2048 || !token.equals(token.strip())
                    || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity")) || borrowed
                    || request.getCookies() != null && request.getCookies().length > 0) { error(response, ErrorCode.UNAUTHORIZED); return; }
            if (!"POST".equals(request.getMethod()) || !WorkflowRuntimeProtocol.PATH.equals(request.getRequestURI())
                    || request.getQueryString() != null || !json(request.getContentType())) { error(response, ErrorCode.FORBIDDEN); return; }
            if (!enabled) { error(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); return; }
            byte[] body = request.getInputStream().readNBytes(WorkflowRuntimeProtocol.MAX_BODY + 1);
            if (body.length == 0 || body.length > WorkflowRuntimeProtocol.MAX_BODY) { error(response, ErrorCode.FORBIDDEN); return; }
            chain.doFilter(new BodyRequest(request, body), response);
        }
        private static boolean json(String type) {
            if (type == null) return false;
            try {
                var media = MediaType.parseMediaType(type);
                return MediaType.APPLICATION_JSON.getType().equals(media.getType()) && MediaType.APPLICATION_JSON.getSubtype().equals(media.getSubtype())
                        && (media.getCharset() == null || StandardCharsets.UTF_8.equals(media.getCharset()));
            } catch (IllegalArgumentException exception) { return false; }
        }
        private static String single(HttpServletRequest request, String name) { var values = Collections.list(request.getHeaders(name)); return values.size() == 1 ? values.getFirst() : null; }
        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value()); response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "Dedicated workflow runtime workload evidence is required."));
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
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous workload body only."); }
            };
        }
    }
}
