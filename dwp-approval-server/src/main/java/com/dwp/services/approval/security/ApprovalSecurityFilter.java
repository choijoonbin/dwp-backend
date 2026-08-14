package com.dwp.services.approval.security;

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
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApprovalSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String PERSON_PUBLIC_ID_HEADER = "X-DWP-Person-Public-ID";
    static final String DISPLAY_NAME_HEADER = "X-DWP-Display-Name-B64";

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public ApprovalSecurityFilter(
            @Value("${dwp.approval.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Approval service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted approval service identity is required.");
            return;
        }

        Long userId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        Set<String> roles = parse(request.getHeader(ROLES_HEADER));
        Set<String> permissions = parse(request.getHeader(PERMISSIONS_HEADER));
        UUID personPublicId = uuid(request.getHeader(PERSON_PUBLIC_ID_HEADER));
        String displayName = decoded(request.getHeader(DISPLAY_NAME_HEADER));
        if (userId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        if (!authorized(request, roles, permissions)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The approval permission required for this operation is missing.");
            return;
        }

        ApprovalRequestContext.set(
                userId, tenantId, personPublicId, displayName, roles, permissions);
        try {
            filterChain.doFilter(request, response);
        } finally {
            ApprovalRequestContext.clear();
        }
    }

    private boolean authorized(
            HttpServletRequest request,
            Set<String> roles,
            Set<String> permissions) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (permissions.isEmpty()) return false;
        if (path.equals("/v1/admin/overview")) {
            return has(permissions, "ADMIN.APPROVAL_OPERATIONS", "VIEW");
        }
        if (path.startsWith("/v1/admin/workflows")) {
            String action = readOnly(method)
                    ? "VIEW"
                    : path.endsWith("/publish")
                            ? "APPROVE"
                            : "POST".equals(method) && path.equals("/v1/admin/workflows")
                                    ? "CREATE"
                                    : "UPDATE";
            return has(permissions, "ADMIN.APPROVAL_DESIGN", action, "MANAGE");
        }
        if (path.startsWith("/v1/admin/forms")) {
            return has(permissions, "ADMIN.APPROVAL_DESIGN",
                    readOnly(method) ? "VIEW" : "UPDATE", "MANAGE");
        }
        if (path.startsWith("/v1/admin/policies")) {
            return has(permissions, "ADMIN.APPROVAL_POLICY", readOnly(method) ? "VIEW" : "MANAGE");
        }
        if (path.startsWith("/v1/admin/operations")) {
            return has(permissions, "ADMIN.APPROVAL_OPERATIONS", readOnly(method) ? "VIEW" : "MANAGE");
        }
        if (path.startsWith("/v1/admin/signatures")) {
            return has(permissions, "ADMIN.APPROVAL_SIGNATURE", readOnly(method) ? "VIEW" : "MANAGE");
        }
        if (path.startsWith("/v1/admin/")) return false;
        if (!has(permissions, "APP.APPROVALS", "VIEW")) return false;
        if (readOnly(method)) {
            if (path.startsWith("/v1/tasks")) {
                return has(permissions, "ACTION.APPROVAL_TASK", "VIEW", "MANAGE");
            }
            if (path.startsWith("/v1/requests")) {
                return has(permissions, "ACTION.APPROVAL_REQUEST", "VIEW", "MANAGE");
            }
            if (path.startsWith("/v1/delegations")) {
                return has(permissions, "ACTION.APPROVAL_DELEGATION", "VIEW", "MANAGE");
            }
            if (path.startsWith("/v1/workflows/published")) {
                return has(permissions, "ACTION.APPROVAL_REQUEST", "VIEW", "CREATE", "MANAGE");
            }
            return path.equals("/v1/home");
        }
        if (path.matches("/v1/tasks/[^/]+/decisions")) {
            return has(permissions, "ACTION.APPROVAL_TASK", "APPROVE", "MANAGE");
        }
        if (path.matches("/v1/tasks/[^/]+/claim")) {
            return has(permissions, "ACTION.APPROVAL_TASK", "UPDATE", "MANAGE");
        }
        if (path.startsWith("/v1/delegations")) {
            return has(permissions, "ACTION.APPROVAL_DELEGATION", "MANAGE");
        }
        if (path.startsWith("/v1/requests")) {
            return has(permissions, "ACTION.APPROVAL_REQUEST", "CREATE", "UPDATE", "MANAGE");
        }
        return false;
    }

    private boolean has(Set<String> permissions, String resource, String... actions) {
        return Arrays.stream(actions).anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
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
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8).trim();
            return decoded.isBlank() || decoded.length() > 200 ? null : decoded;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
