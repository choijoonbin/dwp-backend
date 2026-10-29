package com.dwp.services.notification.security;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
    static final String SERVICE_KEY_ID_HEADER = "X-DWP-Service-Key-ID";

    private final String gatewayToken;
    private final String gatewaySource;
    private final Set<String> allowedProducers;
    private final Map<String, String> producerTokens;
    private final Map<String, Map<String, String>> producerTokenKeyrings;
    private final ObjectMapper objectMapper;

    @Autowired
    public NotificationSecurityFilter(
            @Value("${dwp.notification.service-token:}") String gatewayToken,
            @Value("${dwp.notification.gateway-source:dwp-gateway}") String gatewaySource,
            @Value("${dwp.notification.allowed-producers:}") String allowedProducers,
            @Value("${dwp.notification.producer-tokens:}") String producerTokens,
            @Value("${dwp.notification.producer-token-keyrings:}")
            String producerTokenKeyrings,
            ObjectMapper objectMapper) {
        this.gatewayToken = gatewayToken == null ? "" : gatewayToken.trim();
        this.gatewaySource = gatewaySource == null ? "" : gatewaySource.trim();
        this.allowedProducers = parse(allowedProducers);
        this.producerTokens = parseBindings(producerTokens);
        this.producerTokenKeyrings = parseKeyrings(producerTokenKeyrings);
        this.objectMapper = objectMapper;
        validateIdentityConfiguration();
    }

    public NotificationSecurityFilter(
            String gatewayToken,
            String gatewaySource,
            String allowedProducers,
            String producerTokens,
            ObjectMapper objectMapper) {
        this(gatewayToken, gatewaySource, allowedProducers, producerTokens, "", objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || isHomeProviderPost(request, path)
                || path.equals("/error");
    }

    private boolean isHomeProviderPost(HttpServletRequest request, String path) {
        return "POST".equals(request.getMethod())
                && (HomeWidgetProviderContract.BATCH_PATH.equals(path)
                || HomeWidgetProviderContract.COMMAND_PATH.equals(path));
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
            String presentedKeyId = request.getHeader(SERVICE_KEY_ID_HEADER);
            if (presentedKeyId == null) {
                if (!validProducerIdentity(
                        sourceService,
                        request.getHeader(SERVICE_TOKEN_HEADER))) {
                    writeError(response, NotificationErrorCode.FORBIDDEN);
                    return;
                }
            } else {
                if (!validKeyedProducerIdentity(
                        sourceService,
                        presentedKeyId,
                        request.getHeader(SERVICE_TOKEN_HEADER))) {
                    writeError(response, NotificationErrorCode.FORBIDDEN);
                    return;
                }
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
            if (path.endsWith("/approve")
                    || path.endsWith("/publish")
                    || path.endsWith("/reject")) {
                return has(permissions, resource, "APPROVE");
            }
            if (path.endsWith("/preview")) {
                return has(permissions, resource, "VIEW", "MANAGE", "APPROVE");
            }
            return readOnly
                    ? has(permissions, resource, "VIEW", "MANAGE", "APPROVE")
                    : has(permissions, resource, "MANAGE");
        }
        return has(permissions, "APP.NOTIFICATIONS", "VIEW");
    }

    private String adminResource(String path) {
        if (path.startsWith("/v1/admin/overview")) {
            return "ADMIN.NOTIFICATION_OPERATIONS";
        }
        if (path.startsWith("/v1/admin/noise-quality")) {
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

    private Map<String, Map<String, String>> parseKeyrings(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<String, Map<String, String>> keyrings = new HashMap<>();
        for (String entry : value.split(",")) {
            String[] binding = entry.trim().split("=", 2);
            if (binding.length != 2 || binding[0].isBlank() || binding[1].isBlank()) {
                throw new IllegalArgumentException(
                        "Producer token keyrings must use source-service=key-id:token entries.");
            }
            Map<String, String> keys = new HashMap<>();
            for (String encodedKey : binding[1].split("\\|")) {
                String[] key = encodedKey.trim().split(":", 2);
                if (key.length != 2
                        || !key[0].matches("[a-z0-9][a-z0-9._-]{0,63}")
                        || key[1].isBlank()
                        || !key[1].equals(key[1].trim())
                        || keys.putIfAbsent(key[0], key[1]) != null) {
                    throw new IllegalArgumentException(
                            "Producer token keyrings contain an invalid or duplicate key ID.");
                }
            }
            if (keyrings.putIfAbsent(binding[0].trim(), Map.copyOf(keys)) != null) {
                throw new IllegalArgumentException("Producer token keyring source is duplicated.");
            }
        }
        return Map.copyOf(keyrings);
    }

    private boolean validProducerIdentity(
            String sourceService,
            String presentedToken) {
        if (sourceService == null || !allowedProducers.contains(sourceService)) return false;
        String expectedToken = producerTokens.get(sourceService);
        return expectedToken != null
                && !expectedToken.isBlank()
                && constantTimeEquals(expectedToken, presentedToken);
    }

    private boolean validKeyedProducerIdentity(
            String sourceService,
            String presentedKeyId,
            String presentedToken) {
        if (sourceService == null || !allowedProducers.contains(sourceService)) return false;
        if (!presentedKeyId.equals(presentedKeyId.trim())
                || !presentedKeyId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            return false;
        }
        String expected = producerTokenKeyrings
                .getOrDefault(sourceService, Map.of())
                .get(presentedKeyId);
        return expected != null && constantTimeEquals(expected, presentedToken);
    }

    private void validateIdentityConfiguration() {
        if (gatewayToken.length() < 24 || gatewaySource.isBlank()) {
            throw new IllegalStateException(
                    "Notification gateway identity must use a non-empty source and a 24+ character token.");
        }
        if (!producerTokens.keySet().equals(allowedProducers)) {
            throw new IllegalStateException(
                    "Every allowlisted notification producer must have a legacy token binding "
                            + "before optional rotation keys.");
        }
        if (!allowedProducers.containsAll(producerTokenKeyrings.keySet())) {
            throw new IllegalStateException(
                    "Producer token keyrings must belong to allowlisted notification producers.");
        }
        java.util.List<String> configuredTokens = new java.util.ArrayList<>(
                producerTokens.values());
        producerTokenKeyrings.values().forEach(keyring ->
                configuredTokens.addAll(keyring.values()));
        Set<String> distinctTokens = new java.util.HashSet<>(configuredTokens);
        if (distinctTokens.size() != configuredTokens.size()
                || distinctTokens.contains(gatewayToken)
                || distinctTokens.stream().anyMatch(token -> token.length() < 24)) {
            throw new IllegalStateException(
                    "Notification service identities must use distinct 24+ character tokens.");
        }
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
