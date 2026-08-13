package com.dwp.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Component
public class AuthSessionVerifier implements SessionVerifier {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";

    private final WebClient authClient;
    private final Duration timeout;

    public AuthSessionVerifier(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_AUTH_URL:http://localhost:8001}") String authServiceUrl,
            @Value("${DWP_AUTH_VERIFICATION_TIMEOUT:2s}") Duration timeout) {
        this.authClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.timeout = timeout;
    }

    @Override
    public Mono<VerifiedIdentity> verify(ServerHttpRequest request) {
        String requestedTenant = request.getHeaders().getFirst(TENANT_HEADER);
        boolean tenantAssertionPresent = requestedTenant != null && !requestedTenant.isBlank();

        return authClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/auth/me");
                    String permissionPrefix = permissionPrefix(request);
                    if (permissionPrefix != null) {
                        uriBuilder.queryParam("permissionPrefix", permissionPrefix);
                    }
                    return uriBuilder.build();
                })
                .headers(headers -> copySecurityContext(request.getHeaders(), headers))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(MeEnvelope.class)
                                .filter(envelope -> Boolean.TRUE.equals(envelope.success()))
                                .map(MeEnvelope::data)
                                .filter(data -> data != null
                                        && data.userId() != null
                                        && data.tenantId() != null)
                                .map(data -> new VerifiedIdentity(
                                        data.userId().toString(),
                                        data.tenantId().toString(),
                                        data.roles(),
                                        authorities(data.permissions()),
                                        groupRefs(data.groups())))
                                .filter(identity -> !tenantAssertionPresent
                                        || requestedTenant.equals(identity.tenantId()));
                    }
                    if (response.statusCode() == HttpStatus.UNAUTHORIZED
                            || response.statusCode() == HttpStatus.FORBIDDEN) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    return response.createException().flatMap(Mono::error);
                })
                .timeout(timeout);
    }

    private String permissionPrefix(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if (path.startsWith("/api/platform/v1/admin/audit-control")) {
            return "ADMIN.AUDIT_";
        }
        if (path.startsWith("/api/platform/v1/admin/integrations/productivity")) {
            return "ADMIN.PRODUCTIVITY_CONNECTOR";
        }
        if (path.startsWith("/api/platform/v1/admin/saved-view-ownership")) {
            return "ADMIN.SAVED_VIEW_CUSTODY";
        }
        if (path.startsWith("/api/people/v1/admin/workforce")) {
            return "ADMIN.WORKFORCE_ACCESS";
        }
        if (path.startsWith("/api/people/v1/workforce")) {
            return "DATA.WORKFORCE";
        }
        if (path.startsWith("/api/platform/v1/workspace")) {
            return "APP.";
        }
        if (path.startsWith("/api/agent/v1/ask")) {
            return "APP.";
        }
        return null;
    }

    private List<String> authorities(List<PermissionData> permissions) {
        if (permissions == null) return List.of();
        return permissions.stream()
                .filter(permission -> permission != null
                        && "ALLOW".equalsIgnoreCase(permission.effect()))
                .map(permission -> authority(
                        permission.resourceKey(), permission.permissionCode()))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> groupRefs(List<GroupData> groups) {
        if (groups == null) return List.of();
        return groups.stream()
                .filter(Objects::nonNull)
                .map(GroupData::groupRef)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private String authority(String resourceKey, String permissionCode) {
        if (resourceKey == null || resourceKey.isBlank()
                || permissionCode == null || permissionCode.isBlank()) {
            return null;
        }
        return resourceKey.trim().toUpperCase()
                + ":" + permissionCode.trim().toUpperCase();
    }

    private void copySecurityContext(HttpHeaders source, HttpHeaders target) {
        copyHeader(source, target, HttpHeaders.COOKIE);
        copyHeader(source, target, HttpHeaders.AUTHORIZATION);
        copyHeader(source, target, TENANT_HEADER);
        copyHeader(source, target, CORRELATION_HEADER);
        copyHeader(source, target, TRACE_PARENT_HEADER);
        copyHeader(source, target, HttpHeaders.USER_AGENT);
        target.set(HttpHeaders.ACCEPT, "application/json");
    }

    private void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
        List<String> values = source.get(name);
        if (values != null && !values.isEmpty()) {
            target.put(name, List.copyOf(values));
        }
    }

    private record MeEnvelope(Boolean success, MeData data) {
    }

    private record MeData(
            Long userId,
            Long tenantId,
            List<String> roles,
            List<PermissionData> permissions,
            List<GroupData> groups) {
    }

    private record GroupData(String groupRef, String displayName) {
    }

    private record PermissionData(
            String resourceKey,
            String permissionCode,
            String effect) {
    }
}
