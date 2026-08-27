package com.dwp.gateway.filter;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.security.ProviderSupportSessionVerifier.SupportValidationRejectedException;
import com.dwp.gateway.security.ProviderSupportSessionVerifier.SupportValidationUnavailableException;
import com.dwp.gateway.security.SupportSessionVerifier;
import com.dwp.gateway.security.VerifiedSupportAccess;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
public class SupportSessionContextFilter implements GlobalFilter, Ordered {

    public static final String SUPPORT_COOKIE = "DWP_SUPPORT_SESSION";
    public static final String SUPPORT_SESSION_HEADER = "X-DWP-Support-Session-ID";
    public static final String SUPPORT_SCOPES_HEADER = "X-DWP-Support-Scopes";
    public static final String SUPPORT_REVISION_HEADER = "X-DWP-Support-Revision";
    public static final String PROVIDER_TENANT_HEADER = "X-DWP-Provider-Tenant-ID";
    public static final String ACTOR_TENANT_HEADER = "X-DWP-Actor-Tenant-ID";
    private static final List<String> INTERNAL_HEADERS = List.of(
            SUPPORT_SESSION_HEADER,
            SUPPORT_SCOPES_HEADER,
            SUPPORT_REVISION_HEADER,
            PROVIDER_TENANT_HEADER,
            ACTOR_TENANT_HEADER,
            "X-DWP-Support-Validation-Token",
            "X-DWP-Support-Resource-Method",
            "X-DWP-Support-Resource-Path");
    private static final Set<String> RETIRED_PROVIDER_AUTHORITY_PATHS = Set.of(
            "/api/auth/product-surface-contexts",
            "/api/auth/product-surface-access/evaluate",
            "/api/auth/governed-route-access/evaluate",
            "/api/auth/product-surface-step-up-challenges");

    private final SupportSessionVerifier verifier;
    private final GeneratedProductRouteCatalog routeCatalog;
    private final GatewayDenialAuditSink denialAudit;

    @Autowired
    public SupportSessionContextFilter(
            SupportSessionVerifier verifier,
            GeneratedProductRouteCatalog routeCatalog,
            GatewayDenialAuditSink denialAudit) {
        this.verifier = verifier;
        this.routeCatalog = routeCatalog;
        this.denialAudit = denialAudit;
    }

    /** Test-only compatibility constructor for legacy non-PRODUCT filter fixtures. */
    public SupportSessionContextFilter(SupportSessionVerifier verifier) {
        this.verifier = verifier;
        this.routeCatalog = null;
        this.denialAudit = GatewayDenialAuditSink.NOOP;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String token = supportToken(request);
        boolean providerRequest = path.startsWith("/api/provider/");
        ServerHttpRequest sanitized = sanitize(request, !providerRequest);
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();

        if (token == null || !requiresSupportResolution(request)) {
            return chain.filter(sanitizedExchange);
        }

        return verifier.verify(request, token)
                .switchIfEmpty(Mono.error(new SupportAccessDeniedException()))
                .flatMap(access -> chain.filter(withSupportContext(sanitizedExchange, access)))
                .onErrorResume(
                        SupportAccessDeniedException.class,
                        ignored -> deny(exchange, HttpStatus.FORBIDDEN))
                .onErrorResume(
                        SupportValidationRejectedException.class,
                        exception -> deny(exchange, exception.statusCode()))
                .onErrorResume(
                        SupportValidationUnavailableException.class,
                        ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE))
                .onErrorResume(
                        java.util.concurrent.TimeoutException.class,
                        ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatusCode status) {
        return denialAudit.publish(exchange,
                        GatewayDenialAuditSink.Denial.supportCredential(
                                routeTemplate(exchange.getRequest())))
                .then(Mono.defer(() -> complete(exchange, status)))
                .onErrorResume(ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private String routeTemplate(ServerHttpRequest request) {
        if (routeCatalog == null) return null;
        GeneratedProductRouteCatalog.Match match = routeCatalog.match(
                request.getMethod() == null ? null : request.getMethod().name(),
                request.getURI().getPath(), request.getURI().getRawQuery());
        GeneratedProductRouteCatalog.Route route = match.uniqueRoute();
        return route == null ? null : route.publicPath();
    }

    private boolean requiresSupportResolution(ServerHttpRequest request) {
        if (request.getMethod() == HttpMethod.OPTIONS) return false;
        String path = request.getURI().getPath();
        if (RETIRED_PROVIDER_AUTHORITY_PATHS.contains(path)) return false;
        if (routeCatalog != null && routeCatalog.match(
                request.getMethod() == null ? null : request.getMethod().name(), path,
                request.getURI().getRawQuery()).status()
                != GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED) return true;
        return path.startsWith("/api/platform/")
                || path.startsWith("/api/people/");
    }

    private ServerWebExchange withSupportContext(
            ServerWebExchange exchange,
            VerifiedSupportAccess access) {
        String actorTenant = exchange.getRequest().getHeaders()
                .getFirst(VerifiedIdentityFilter.TENANT_HEADER);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(VerifiedIdentityFilter.TENANT_HEADER, access.authTenantId());
                    headers.set(SUPPORT_SESSION_HEADER, access.supportSessionId());
                    headers.set(SUPPORT_SCOPES_HEADER, String.join(",", access.scopes()));
                    headers.set(SUPPORT_REVISION_HEADER, "support-v" + access.version());
                    headers.set(PROVIDER_TENANT_HEADER, access.providerTenantId());
                    if (actorTenant != null) headers.set(ACTOR_TENANT_HEADER, actorTenant);
                })
                .build();
        exchange.getAttributes().put("dwp.supportSessionId", access.supportSessionId());
        exchange.getAttributes().put("dwp.supportTenantId", access.authTenantId());
        return exchange.mutate().request(request).build();
    }

    private ServerHttpRequest sanitize(ServerHttpRequest request, boolean removeSupportCookie) {
        return request.mutate().headers(headers -> {
            INTERNAL_HEADERS.forEach(headers::remove);
            if (removeSupportCookie) removeSupportCookie(headers);
        }).build();
    }

    private void removeSupportCookie(HttpHeaders headers) {
        List<String> cookies = headers.remove(HttpHeaders.COOKIE);
        if (cookies == null) return;
        cookies.stream()
                .map(this::withoutSupportCookie)
                .filter(value -> !value.isBlank())
                .forEach(value -> headers.add(HttpHeaders.COOKIE, value));
    }

    private String withoutSupportCookie(String cookieHeader) {
        return Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .filter(value -> !value.startsWith(SUPPORT_COOKIE + "="))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private String supportToken(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(SUPPORT_COOKIE);
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }

    private Mono<Void> complete(ServerWebExchange exchange, HttpStatusCode status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        // Identity must be durable before resolving the support credential, while
        // the resolved context must exist before the provider data-plane boundary.
        return -98;
    }

    private static final class SupportAccessDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
