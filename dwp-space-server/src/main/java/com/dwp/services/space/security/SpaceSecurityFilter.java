package com.dwp.services.space.security;

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
public class SpaceSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String GROUP_REFS_HEADER = "X-DWP-Group-Refs";
    static final String PERSON_PUBLIC_ID_HEADER = "X-DWP-Person-Public-ID";
    static final String DISPLAY_NAME_HEADER = "X-DWP-Display-Name-B64";

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public SpaceSecurityFilter(
            @Value("${dwp.space.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Space service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted Space service identity is required.");
            return;
        }

        Long userId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        Set<String> roles = parse(request.getHeader(ROLES_HEADER));
        Set<String> permissions = parse(request.getHeader(PERMISSIONS_HEADER));
        Set<String> groups = parse(request.getHeader(GROUP_REFS_HEADER));
        if (userId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        if (!authorized(request, permissions)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The Space permission required for this operation is missing.");
            return;
        }

        SpaceRequestContext.set(new SpaceRequestContext.Subject(
                userId,
                tenantId,
                uuid(request.getHeader(PERSON_PUBLIC_ID_HEADER)),
                decoded(request.getHeader(DISPLAY_NAME_HEADER)),
                roles,
                permissions,
                groups));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SpaceRequestContext.clear();
        }
    }

    private boolean authorized(HttpServletRequest request, Set<String> permissions) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/v1/admin/templates")) {
            if (readOnly(method) && path.equals("/v1/admin/templates")) {
                return has(permissions, "ADMIN.SPACE_TEMPLATES", "VIEW", "MANAGE");
            }
            if ("POST".equals(method) && path.equals("/v1/admin/templates")) {
                return has(permissions, "ADMIN.SPACE_TEMPLATES", "CREATE", "MANAGE");
            }
            if ("PUT".equals(method) && directChild(path, "/v1/admin/templates")) {
                return has(permissions, "ADMIN.SPACE_TEMPLATES", "UPDATE", "MANAGE");
            }
            return false;
        }
        if (path.startsWith("/v1/admin/content-reviews")) {
            return has(permissions, "ADMIN.SPACE_COMPLIANCE", readOnly(method) ? "VIEW" : "APPROVE", "MANAGE");
        }
        if (path.startsWith("/v1/admin/lifecycle")) {
            if (readOnly(method) && path.equals("/v1/admin/lifecycle")) {
                return has(permissions, "ADMIN.SPACE_ACCESS_REVIEW", "VIEW", "APPROVE", "MANAGE");
            }
            if ("POST".equals(method)
                    && itemAction(path, "/v1/admin/lifecycle", "decision")) {
                return has(permissions, "ADMIN.SPACE_ACCESS_REVIEW", "APPROVE", "MANAGE");
            }
            return false;
        }
        if (path.startsWith("/v1/admin/")) {
            return has(permissions, "ADMIN.SPACE_GOVERNANCE", readOnly(method) ? "VIEW" : "MANAGE");
        }
        if (path.equals("/v1/requests") && !readOnly(method)) {
            return has(permissions, "ACTION.SPACE_REQUEST", "CREATE", "MANAGE");
        }
        if (path.contains("/content") && !readOnly(method)) {
            return has(permissions, "ACTION.SPACE_CONTENT", "CREATE", "UPDATE", "MANAGE");
        }
        return has(permissions, "APP.SPACES", "VIEW");
    }

    private boolean directChild(String path, String collection) {
        String prefix = collection + "/";
        if (!path.startsWith(prefix)) return false;
        String child = path.substring(prefix.length());
        return !child.isBlank() && !child.contains("/");
    }

    private boolean itemAction(String path, String collection, String action) {
        String prefix = collection + "/";
        String suffix = "/" + action;
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) return false;
        String item = path.substring(prefix.length(), path.length() - suffix.length());
        return !item.isBlank() && !item.contains("/");
    }

    private boolean has(Set<String> permissions, String resource, String... actions) {
        return Arrays.stream(actions).anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(candidate -> !candidate.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long positiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
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
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
