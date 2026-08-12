package com.dwp.services.platform.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.filter.ApiHistoryServletFilter;
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
public class PlatformSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";
    static final String SUPPORT_SCOPES_HEADER = "X-DWP-Support-Scopes";
    static final String ACTOR_TENANT_HEADER = "X-DWP-Actor-Tenant-ID";
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    private static final List<String> SUPPORT_CONFIGURATION_PATHS = List.of(
            "/v1/admin/tenant-branding",
            "/v1/admin/home-experience",
            "/v1/admin/announcements");
    private static final List<String> SUPPORT_CONFIGURATION_ASSET_PATHS = List.of(
            "/v1/tenant-branding",
            "/v1/home-experience",
            "/v1/announcements");

    private final String serviceToken;
    private final String runtimeServiceToken;
    private final ObjectMapper objectMapper;

    public PlatformSecurityFilter(
            @Value("${dwp.platform.service-token:}") String serviceToken,
            @Value("${dwp.platform.runtime-service-token:}") String runtimeServiceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.runtimeServiceToken = runtimeServiceToken == null ? "" : runtimeServiceToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith(ApiHistoryServletFilter.COLLECTOR_PATH)
                || path.startsWith("/internal/audit/events")
                || path.startsWith("/internal/provider/")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean runtimeRead = isRuntimeRead(request);
        if (serviceToken.isBlank() && (!runtimeRead || runtimeServiceToken.isBlank())) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR, "Platform service identity is not configured.");
            return;
        }
        String providedToken = request.getHeader(SERVICE_TOKEN_HEADER);
        boolean gatewayIdentity = !serviceToken.isBlank()
                && constantTimeEquals(serviceToken, providedToken);
        boolean runtimeIdentity = runtimeRead
                && !runtimeServiceToken.isBlank()
                && constantTimeEquals(runtimeServiceToken, providedToken);
        if (!gatewayIdentity && !runtimeIdentity) {
            writeError(response, ErrorCode.UNAUTHORIZED, "Trusted platform service identity is required.");
            return;
        }

        Long actorId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        if (actorId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED, "Verified user and tenant identity are required.");
            return;
        }
        String path = request.getRequestURI();
        boolean supportAccess = !isBlank(request.getHeader(SUPPORT_SESSION_HEADER));
        if (supportAccess && !authorizedSupportRequest(request)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The support session does not permit this platform resource.");
            return;
        }
        boolean auditAdminPath = path.startsWith("/v1/admin/audit-control");
        if (auditAdminPath && isBlank(request.getHeader(PERMISSIONS_HEADER))) {
            writeError(response, ErrorCode.FORBIDDEN, "Audit permission is required.");
            return;
        }
        boolean workspacePath = path.startsWith("/v1/workspace");
        if (workspacePath && isBlank(request.getHeader(PERMISSIONS_HEADER))) {
            writeError(response, ErrorCode.FORBIDDEN, "Workspace permission is required.");
            return;
        }
        if (!supportAccess && !auditAdminPath && path.startsWith("/v1/admin/")
                && !hasRole(request.getHeader(ROLES_HEADER), ADMIN_ROLES)) {
            writeError(response, ErrorCode.FORBIDDEN, "Tenant administrator permission is required.");
            return;
        }

        RequestActorContext.set(actorId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestActorContext.clear();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean hasRole(String rolesHeader, Set<String> allowedRoles) {
        if (rolesHeader == null || rolesHeader.isBlank()) return false;
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(allowedRoles::contains);
    }

    private boolean authorizedSupportRequest(HttpServletRequest request) {
        if (positiveLong(request.getHeader(ACTOR_TENANT_HEADER)) == null) return false;
        Set<String> scopes = parseValues(request.getHeader(SUPPORT_SCOPES_HEADER));
        String path = request.getRequestURI();
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (matches(path, SUPPORT_CONFIGURATION_PATHS)) {
            return read
                    ? scopes.contains("TENANT_CONFIGURATION_READ")
                    : scopes.contains("TENANT_CONFIGURATION_WRITE");
        }
        return read
                && matches(path, SUPPORT_CONFIGURATION_ASSET_PATHS)
                && scopes.contains("TENANT_CONFIGURATION_READ");
    }

    private Set<String> parseValues(String header) {
        if (isBlank(header)) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean matches(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private boolean isRuntimeRead(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return ("GET".equals(method) || "HEAD".equals(method))
                && (path.startsWith("/v1/catalog/")
                || path.startsWith("/v1/reference-data/")
                || path.equals("/v1/workspace/work-items")
                || path.equals("/v1/workspace/productivity/items"));
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
