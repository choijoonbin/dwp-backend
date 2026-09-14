package com.dwp.services.auth.config;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
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
public class ApprovalFormUserInternalSecurityConfig {
    public static final String PREFIX = "/internal/auth/v1/approval-form-user-directory";
    public static final String TOKEN_HEADER = "X-DWP-Approval-Form-User-Token";
    private static final Set<String> PATHS = Set.of(PREFIX + "/search", PREFIX + "/resolve");
    private static final Set<String> BORROWED_HEADERS = Set.of("Authorization", "X-DWP-Service-Token",
            "X-DWP-Identity-Sync-Token", "X-DWP-Approval-Recovery-Token", "X-DWP-Product-Surface-Token",
            "X-DWP-Meeting-Followup-Token", "X-DWP-Tenant-ID", "X-DWP-User-ID", "X-DWP-Roles",
            "X-DWP-Permissions", "X-DWP-Support-Session-ID", "X-DWP-Provider-Tenant-ID", "X-DWP-Identity-Plane",
            "X-DWP-Context-Key", "X-DWP-Context-Scope-Key", "X-DWP-Current-Decision-Revision", "X-DWP-Active-Access-Mode");

    @Bean
    @Order(-1)
    SecurityFilterChain approvalFormUserInternalSecurityFilterChain(HttpSecurity http,
            @Value("${dwp.auth.approval-form-user-token:}") String token, ObjectMapper mapper) throws Exception {
        http.securityMatcher(request -> directoryPath(request.getRequestURI()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new ApprovalFormUserTokenFilter(token, mapper), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static boolean directoryPath(String path) {
        if (path.equals(PREFIX) || path.startsWith(PREFIX + '/')) return true;
        try {
            return PathPatternParser.defaultInstance.parse(PREFIX + "/**").matches(PathContainer.parsePath(path));
        } catch (IllegalArgumentException exception) {
            return path.contains("approval-form-user-directory");
        }
    }

    static final class ApprovalFormUserTokenFilter extends OncePerRequestFilter {
        private final byte[] token;
        private final ObjectMapper mapper;

        ApprovalFormUserTokenFilter(String token, ObjectMapper mapper) {
            this.token = (token == null ? "" : token.strip()).getBytes(StandardCharsets.UTF_8);
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String supplied = single(request, TOKEN_HEADER);
            boolean validToken = token.length > 0 & MessageDigest.isEqual(token,
                    supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8));
            if (!validToken || !"dwp-approval-server".equals(single(request, "X-DWP-Service-Identity"))
                    || (request.getCookies() != null && request.getCookies().length > 0)
                    || BORROWED_HEADERS.stream().anyMatch(header -> request.getHeader(header) != null)) {
                error(response, ErrorCode.UNAUTHORIZED);
                return;
            }
            if (!"POST".equals(request.getMethod()) || !PATHS.contains(request.getRequestURI())
                    || request.getQueryString() != null || !acceptsJson(request)) {
                error(response, ErrorCode.FORBIDDEN);
                return;
            }
            chain.doFilter(request, response);
        }

        private String single(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name));
            return values.size() == 1 ? values.getFirst() : null;
        }

        private boolean acceptsJson(HttpServletRequest request) {
            var values = Collections.list(request.getHeaders("Accept"));
            if (values.isEmpty()) return true;
            try {
                return MediaType.parseMediaTypes(values).stream().anyMatch(type -> type.getQualityValue() > 0
                        && MediaType.APPLICATION_JSON.isCompatibleWith(type));
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private void error(HttpServletResponse response, ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiResponse.error(code,
                    "Exact purpose-bound approval directory service identity is required."));
        }
    }
}
