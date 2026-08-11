package com.dwp.services.people.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PeopleSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";
    static final String SUPPORT_SCOPES_HEADER = "X-DWP-Support-Scopes";
    static final String ACTOR_TENANT_HEADER = "X-DWP-Actor-Tenant-ID";
    private static final Set<String> ADMIN_ROLES =
            Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "HR_ADMIN", "PEOPLE_ADMIN");
    private static final Set<String> WORKFORCE_ROLES =
            Set.of("ADMIN", "HR_ADMIN", "PEOPLE_ADMIN");
    private static final List<String> SUPPORT_WORKFORCE_PATHS =
            List.of("/v1/people", "/v1/org-chart", "/v1/workforce");

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public PeopleSecurityFilter(
            @Value("${dwp.people.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/internal/provider/")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "People service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted people service identity is required.");
            return;
        }

        Long actorId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        Set<String> roles = parseRoles(request.getHeader(ROLES_HEADER));
        if (actorId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        boolean supportAccess = request.getHeader(SUPPORT_SESSION_HEADER) != null
                && !request.getHeader(SUPPORT_SESSION_HEADER).isBlank();
        if (supportAccess && !authorizedSupportRequest(request)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The support session does not permit this workforce resource.");
            return;
        }
        if (!supportAccess && request.getRequestURI().startsWith("/v1/admin/")
                && roles.stream().noneMatch(ADMIN_ROLES::contains)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "People administrator permission is required.");
            return;
        }
        if (!supportAccess && request.getRequestURI().startsWith("/v1/workforce/")
                && roles.stream().noneMatch(WORKFORCE_ROLES::contains)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Workforce operations permission is required.");
            return;
        }

        PeopleRequestContext.set(actorId, tenantId, roles);
        try {
            filterChain.doFilter(request, response);
        } finally {
            PeopleRequestContext.clear();
        }
    }

    private Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean authorizedSupportRequest(HttpServletRequest request) {
        if (positiveLong(request.getHeader(ACTOR_TENANT_HEADER)) == null) return false;
        if (!("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) return false;
        if (!parseRoles(request.getHeader(SUPPORT_SCOPES_HEADER)).contains("WORKFORCE_READ")) {
            return false;
        }
        String path = request.getRequestURI();
        return SUPPORT_WORKFORCE_PATHS.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }
}
