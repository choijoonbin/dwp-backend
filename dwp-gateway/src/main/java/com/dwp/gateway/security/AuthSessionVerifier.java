package com.dwp.gateway.security;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Component
public class AuthSessionVerifier implements SessionVerifier {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final WebClient authClient;
    private final Duration timeout;
    private final AsyncCache<String, VerifiedIdentity> verifiedIdentityCache;

    @Autowired
    public AuthSessionVerifier(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_AUTH_URL:http://localhost:8001}") String authServiceUrl,
            @Value("${DWP_AUTH_VERIFICATION_TIMEOUT:2s}") Duration timeout,
            @Value("${DWP_AUTH_VERIFICATION_CACHE_TTL:3s}") Duration cacheTtl,
            @Value("${DWP_AUTH_VERIFICATION_CACHE_MAX_ENTRIES:10000}") long cacheMaxEntries) {
        this.authClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.timeout = timeout;
        if (cacheTtl.isNegative() || cacheTtl.isZero() || cacheTtl.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Auth verification cache TTL must be between 1ms and 30s.");
        }
        if (cacheMaxEntries < 100 || cacheMaxEntries > 100_000) {
            throw new IllegalArgumentException("Auth verification cache size must be between 100 and 100000.");
        }
        this.verifiedIdentityCache = Caffeine.newBuilder()
                .maximumSize(cacheMaxEntries)
                .expireAfterWrite(cacheTtl)
                .buildAsync();
    }

    public AuthSessionVerifier(
            WebClient.Builder webClientBuilder,
            String authServiceUrl,
            Duration timeout) {
        this(webClientBuilder, authServiceUrl, timeout, Duration.ofSeconds(3), 10_000);
    }

    @Override
    public Mono<VerifiedIdentity> verify(ServerHttpRequest request) {
        if (!isLowRiskCacheableRead(request)) {
            return verifyUncached(request);
        }
        String cacheKey = cacheKey(request);
        return Mono.fromFuture(verifiedIdentityCache.get(
                cacheKey,
                (ignored, executor) -> verifyUncached(request).toFuture()));
    }

    private boolean isLowRiskCacheableRead(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) return false;
        if (permissionPrefix(request) != null) return false;

        String path = request.getURI().getPath();
        return path.equals("/api/platform/v1/home-experience/background")
                || path.equals("/api/platform/v1/tenant-branding")
                || path.startsWith("/api/platform/v1/reference-data/");
    }

    private Mono<VerifiedIdentity> verifyUncached(ServerHttpRequest request) {
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
                                        groupRefs(data.groups()),
                                        resourceRoles(data.resourceRoles()),
                                        data.personPublicId(),
                                        data.displayName()))
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

    private String cacheKey(ServerHttpRequest request) {
        String material = String.join("\n",
                header(request, HttpHeaders.COOKIE),
                header(request, HttpHeaders.AUTHORIZATION),
                header(request, TENANT_HEADER),
                Objects.toString(permissionPrefix(request), ""));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String header(ServerHttpRequest request, String name) {
        return Objects.toString(request.getHeaders().getFirst(name), "");
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
        if (path.startsWith("/api/people/v1/hr")) {
            return "APP.HCM,APP.HRIS,DATA.HR_";
        }
        if (path.equals("/api/platform/v1/home/overview")) {
            return "APP.WORK,APP.ACTIVITY";
        }
        if (path.startsWith("/api/platform/v1/workspace")) {
            return "APP.";
        }
        if (path.startsWith("/api/platform/v1/communications")) {
            return "APP.COMMUNICATIONS";
        }
        if (path.startsWith("/api/platform/v1/admin/announcements")) {
            return "ADMIN.COMMUNICATIONS";
        }
        if (path.startsWith("/api/platform/v1/admin/services/catalog")) {
            return "ADMIN.SERVICE_CATALOG";
        }
        if (path.startsWith("/api/platform/v1/admin/services/requests")) {
            return "ADMIN.SERVICE_OPERATIONS";
        }
        if (path.startsWith("/api/platform/v1/admin/calendar")) {
            return "ADMIN.CALENDAR";
        }
        if (path.startsWith("/api/platform/v1/calendar")) {
            return "APP.CALENDAR";
        }
        if (path.startsWith("/api/approvals/v1/admin/")) {
            return "ADMIN.APPROVAL_";
        }
        if (path.equals("/api/approvals/v1/home")) {
            return "APP.APPROVALS,ACTION.APPROVAL_,ADMIN.APPROVAL_";
        }
        if (path.startsWith("/api/approvals/v1/")) {
            return "APP.APPROVALS,ACTION.APPROVAL_";
        }
        if (path.startsWith("/api/platform/v1/services")) {
            return "APP.EMPLOYEE_SERVICES";
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

    private List<String> resourceRoles(List<ResourceRoleData> roles) {
        if (roles == null) return List.of();
        return roles.stream()
                .filter(Objects::nonNull)
                .map(role -> resourceRole(role.responsibilityCode(), role.resourceKey()))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private String resourceRole(String responsibilityCode, String resourceKey) {
        if (responsibilityCode == null || resourceKey == null) return null;
        String responsibility = responsibilityCode.trim().toUpperCase();
        String resource = resourceKey.trim().toUpperCase();
        if (!responsibility.matches("[A-Z][A-Z0-9_]{2,49}")
                || !resource.matches("[A-Z][A-Z0-9_.-]{2,254}")) {
            return null;
        }
        return responsibility + "@" + resource;
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
        copyHeader(source, target, TRACE_STATE_HEADER);
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
            String personPublicId,
            String displayName,
            List<String> roles,
            List<PermissionData> permissions,
            List<GroupData> groups,
            List<ResourceRoleData> resourceRoles) {
    }

    private record GroupData(String groupRef, String displayName) {
    }

    private record PermissionData(
            String resourceKey,
            String permissionCode,
            String effect) {
    }

    private record ResourceRoleData(String responsibilityCode, String resourceKey) {
    }
}
