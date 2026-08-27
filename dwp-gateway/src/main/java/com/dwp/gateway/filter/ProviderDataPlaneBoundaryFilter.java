package com.dwp.gateway.filter;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Prevents a provider control-plane identity from using its bootstrap auth
 * tenant as ambient access to customer data-plane APIs. Support access is
 * accepted only after {@link SupportSessionContextFilter} has resolved the
 * opaque support credential into trusted internal headers.
 */
@Component
public class ProviderDataPlaneBoundaryFilter implements GlobalFilter, Ordered {

    private static final String EXPERIENCE_PREVIEW_PATH =
            "/api/platform/v1/admin/tenant-experience-preview";
    private final GatewayDenialAuditSink denialAudit;

    @Autowired
    public ProviderDataPlaneBoundaryFilter(GatewayDenialAuditSink denialAudit) {
        this.denialAudit = denialAudit;
    }

    /** Test-only constructor retaining the pre-audit isolated filter fixture. */
    public ProviderDataPlaneBoundaryFilter() {
        this(GatewayDenialAuditSink.NOOP);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS
                || !path.startsWith("/api/")
                || !isProviderIdentity(exchange)
                || providerControlPath(exchange)
                || verifiedSupportPath(exchange)) {
            return chain.filter(exchange);
        }
        return denialAudit.publish(exchange,
                        GatewayDenialAuditSink.Denial.providerDataPlane(null))
                .then(Mono.defer(() -> complete(exchange, HttpStatus.FORBIDDEN)))
                .onErrorResume(ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private Mono<Void> complete(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private boolean providerControlPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();
        if (path.equals("/api/provider") || path.startsWith("/api/provider/")) return true;
        if (method == HttpMethod.GET) {
            return path.equals("/api/auth/me")
                    || path.equals("/api/auth/permissions")
                    || path.equals("/api/auth/sessions");
        }
        if (method == HttpMethod.PATCH) return path.equals("/api/auth/me/locale");
        if (method == HttpMethod.POST) {
            return path.equals("/api/auth/session/refresh")
                    || path.equals("/api/auth/sessions/logout-others")
                    || path.equals("/api/agent/v1/plans/preview");
        }
        return method == HttpMethod.DELETE && ownedSessionPath(path);
    }

    private boolean ownedSessionPath(String path) {
        String prefix = "/api/auth/sessions/";
        if (!path.startsWith(prefix)) return false;
        String value = path.substring(prefix.length());
        if (value.isBlank() || value.indexOf('/') >= 0) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean verifiedSupportPath(ServerWebExchange exchange) {
        if (!verifiedSupportContext(exchange)) return false;
        String path = exchange.getRequest().getURI().getPath();
        return exchange.getRequest().getMethod() == HttpMethod.GET
                && path.equals(EXPERIENCE_PREVIEW_PATH);
    }

    private boolean verifiedSupportContext(ServerWebExchange exchange) {
        String supportSessionId = exchange.getRequest().getHeaders()
                .getFirst(SupportSessionContextFilter.SUPPORT_SESSION_HEADER);
        String actorTenantId = exchange.getRequest().getHeaders()
                .getFirst(SupportSessionContextFilter.ACTOR_TENANT_HEADER);
        return supportSessionId != null && !supportSessionId.isBlank()
                && actorTenantId != null && !actorTenantId.isBlank()
                && supportSessionId.equals(exchange.getAttribute("dwp.supportSessionId"));
    }

    private boolean hasProviderRole(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) return false;
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.startsWith("PROVIDER_"));
    }

    private boolean isProviderIdentity(ServerWebExchange exchange) {
        String identityPlane = exchange.getRequest().getHeaders()
                .getFirst(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER);
        return "PROVIDER".equalsIgnoreCase(identityPlane)
                || hasProviderRole(exchange.getRequest().getHeaders()
                        .getFirst(VerifiedIdentityFilter.ROLES_HEADER));
    }

    @Override
    public int getOrder() {
        // Reject ambient provider access before product-surface authority lookup.
        // This keeps every tenant data-plane path fail-closed as 403 even when a
        // downstream authority service is unavailable.
        return -97;
    }
}
