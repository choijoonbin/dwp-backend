package com.dwp.services.notification.security;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.common.NotificationErrorCode;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class NotificationSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String SOURCE_SERVICE_HEADER = "X-DWP-Source-Service";

    private final String gatewayToken;
    private final String gatewaySource;
    private final Set<String> allowedProducers;
    private final Map<String, String> producerTokens;
    private final ObjectMapper objectMapper;

    public NotificationSecurityFilter(
            @Value("${dwp.notification.service-token:}") String gatewayToken,
            @Value("${dwp.notification.gateway-source:dwp-gateway}") String gatewaySource,
            @Value("${dwp.notification.allowed-producers:}") String allowedProducers,
            @Value("${dwp.notification.producer-tokens:}") String producerTokens,
            ObjectMapper objectMapper) {
        this.gatewayToken = gatewayToken == null ? "" : gatewayToken.trim();
        this.gatewaySource = gatewaySource == null ? "" : gatewaySource.trim();
        this.allowedProducers = parse(allowedProducers);
        this.producerTokens = parseBindings(producerTokens);
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
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        if (tenantId == null) {
            writeError(response, NotificationErrorCode.UNAUTHORIZED);
            return;
        }

        String path = request.getRequestURI();
        boolean internal = path.startsWith("/internal/v1/");
        Long userId = positiveLong(request.getHeader(USER_HEADER));
        Set<String> roles = parse(request.getHeader(ROLES_HEADER));
        Set<String> permissions = parse(request.getHeader(PERMISSIONS_HEADER));
        String sourceService = normalized(request.getHeader(SOURCE_SERVICE_HEADER));

        if (internal) {
            if (!validProducerIdentity(
                    sourceService, request.getHeader(SERVICE_TOKEN_HEADER))) {
                writeError(response, NotificationErrorCode.FORBIDDEN);
                return;
            }
        } else {
            if (gatewayToken.isBlank() || gatewaySource.isBlank()) {
                writeError(response, NotificationErrorCode.SERVICE_NOT_CONFIGURED);
                return;
            }
            if (!gatewaySource.equals(sourceService)
                    || !constantTimeEquals(
                            gatewayToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
                writeError(response, NotificationErrorCode.UNAUTHORIZED);
                return;
            }
            if (userId == null) {
                writeError(response, NotificationErrorCode.UNAUTHORIZED);
                return;
            }
            if (!authorized(path, request.getMethod(), permissions)) {
                writeError(response, NotificationErrorCode.FORBIDDEN);
                return;
            }
        }

        NotificationRequestContext.set(new NotificationRequestContext.Actor(
                tenantId, userId, roles, permissions, internal, sourceService));
        try {
            filterChain.doFilter(request, response);
        } finally {
            NotificationRequestContext.clear();
        }
    }

    private boolean authorized(
            String path,
            String method,
            Set<String> permissions) {
        if (!path.startsWith("/v1/")) return false;
        boolean readOnly = "GET".equals(method) || "HEAD".equals(method);
        if (path.startsWith("/v1/admin/")) {
            String resource = adminResource(path);
            if (resource == null) return false;
            if (path.endsWith("/approve") || path.endsWith("/publish")) {
                return has(permissions, resource, "APPROVE");
            }
            return readOnly
                    ? has(permissions, resource, "VIEW", "MANAGE", "APPROVE")
                    : has(permissions, resource, "MANAGE", "APPROVE");
        }
        return has(permissions, "APP.NOTIFICATIONS", "VIEW");
    }

    private String adminResource(String path) {
        if (path.startsWith("/v1/admin/overview")) {
            return "ADMIN.NOTIFICATION_OPERATIONS";
        }
        if (path.startsWith("/v1/admin/types")) return "ADMIN.NOTIFICATION_CONTRACT";
        if (path.startsWith("/v1/admin/templates")) return "ADMIN.NOTIFICATION_TEMPLATE";
        if (path.startsWith("/v1/admin/policies")) return "ADMIN.NOTIFICATION_POLICY";
        if (path.startsWith("/v1/admin/operations")
                || path.startsWith("/v1/admin/suppressions")) {
            return "ADMIN.NOTIFICATION_OPERATIONS";
        }
        if (path.startsWith("/v1/admin/audit")) return "ADMIN.NOTIFICATION_AUDIT";
        return null;
    }

    private boolean has(Set<String> permissions, String resource, String... actions) {
        return Arrays.stream(actions)
                .anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private static Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, String> parseBindings(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, String> bindings = new HashMap<>();
        for (String entry : value.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Producer tokens must use source-service=token entries.");
            }
            if (bindings.put(parts[0].trim(), parts[1].trim()) != null) {
                throw new IllegalArgumentException("Producer source identity is duplicated.");
            }
        }
        return Map.copyOf(bindings);
    }

    private boolean validProducerIdentity(String sourceService, String presentedToken) {
        if (sourceService == null || !allowedProducers.contains(sourceService)) return false;
        String expectedToken = producerTokens.get(sourceService);
        return expectedToken != null
                && !expectedToken.isBlank()
                && constantTimeEquals(expectedToken, presentedToken);
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= 100 ? normalized : null;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, NotificationErrorCode code)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(), ApiResponse.error(code, code.message(), null));
    }
}
