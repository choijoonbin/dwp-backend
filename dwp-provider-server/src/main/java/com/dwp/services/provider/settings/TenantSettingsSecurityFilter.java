package com.dwp.services.provider.settings;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.RolePlaneBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class TenantSettingsSecurityFilter extends OncePerRequestFilter {

    private static final String PATH = "/v1/tenant/settings";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String ROLES_HEADER = "X-DWP-Roles";
    private static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    private static final String AUTH_SESSION_ID_HEADER = "X-DWP-Auth-Session-ID";
    private static final String IDENTITY_PLANE_HEADER = "X-DWP-Identity-Plane";
    private static final List<String> SUPPORT_CONTEXT_HEADERS = List.of(
            "X-DWP-Support-Session-ID",
            "X-DWP-Support-Scopes",
            "X-DWP-Support-Revision",
            "X-DWP-Provider-Tenant-ID",
            "X-DWP-Actor-Tenant-ID");
    private static final Pattern PERMISSION = Pattern.compile(
            "[A-Z][A-Z0-9_.-]{1,79}:[A-Z][A-Z0-9_.-]{1,49}");
    static final String DOMAIN_READ_PERMISSION = "ADMIN.IDENTITY_PROVISIONING:VIEW";
    static final String GOVERNANCE_READ_PERMISSION = "ADMIN.AUDIT_VIEW:VIEW";
    static final String PLAN_READ_PERMISSION = "ADMIN.APP_GOVERNANCE:VIEW";

    private final String serviceToken;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantSettingsSecurityFilter(
            @Value("${dwp.provider.service-token:}") String serviceToken,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (serviceToken.isBlank()
                || !constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            error(response, ErrorCode.UNAUTHORIZED, "Trusted gateway identity is required.");
            return;
        }
        Long userId = positiveLong(request.getHeader(USER_HEADER));
        Long authTenantId = positiveLong(request.getHeader(TENANT_HEADER));
        UUID sessionId = uuid(request.getHeader(AUTH_SESSION_ID_HEADER));
        Set<String> roles = Arrays.stream(value(request.getHeader(ROLES_HEADER)).split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> permissions = values(request.getHeader(PERMISSIONS_HEADER));
        boolean tenantPlane = "TENANT".equalsIgnoreCase(request.getHeader(IDENTITY_PLANE_HEADER));
        if (userId == null || authTenantId == null || sessionId == null || roles.isEmpty()
                || !tenantPlane || RolePlaneBoundary.isProviderIdentity(roles)
                || RolePlaneBoundary.hasConflict(roles) || hasSupportContext(request)) {
            error(response, ErrorCode.FORBIDDEN, "An active tenant identity is required.");
            return;
        }
        String requiredPermission = requiredPermission(request);
        if (requiredPermission != null && !permissions.contains(requiredPermission)) {
            error(response, ErrorCode.FORBIDDEN,
                    "The tenant resource permission required for this projection is missing.");
            return;
        }
        UUID providerTenantId = jdbc.query("""
                SELECT provider_tenant_id FROM prv_tenants
                 WHERE auth_tenant_id = ? AND lifecycle_state IN ('ACTIVE', 'SUSPENDED')
                """, result -> result.next()
                        ? result.getObject("provider_tenant_id", UUID.class) : null,
                authTenantId);
        if (providerTenantId == null) {
            error(response, ErrorCode.NOT_FOUND, "The tenant settings projection is not mapped.");
            return;
        }
        TenantSettingsRequestContext.set(new TenantSettingsRequestContext.Actor(
                authTenantId, userId, sessionId, providerTenantId, roles, permissions));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantSettingsRequestContext.clear();
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private Set<String> values(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(PERMISSION.asMatchPredicate())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean hasSupportContext(HttpServletRequest request) {
        return SUPPORT_CONTEXT_HEADERS.stream()
                .map(request::getHeader)
                .anyMatch(header -> header != null && !header.isBlank());
    }

    private String requiredPermission(HttpServletRequest request) {
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return null;
        return switch (request.getRequestURI()) {
            case PATH + "/provider-domains" -> DOMAIN_READ_PERMISSION;
            case PATH + "/data-governance-observation" -> GOVERNANCE_READ_PERMISSION;
            case PATH + "/plan-eligibility" -> PLAN_READ_PERMISSION;
            default -> null;
        };
    }

    private void error(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
