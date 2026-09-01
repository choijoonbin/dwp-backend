package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.observability.api.ApiHistoryAttributes;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Issues an in-memory capability only when an authenticated public product-surface route is
 * forwarded to its exact gateway-owned handler. The capability cannot be supplied in an HTTP
 * header, so the handler can reject direct requests to its internal {@code /_gateway/**} URI.
 */
@Component
public final class ProductSurfaceForwardingGuardFilter implements GlobalFilter, Ordered {

    static final String FORWARDING_ATTRIBUTE =
            "dwp.gateway.product-surface.trusted-forward";
    private static final Object FORWARDING_CAPABILITY = new Object();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getAttributes().remove(FORWARDING_ATTRIBUTE);

        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        Endpoint endpoint = route == null ? null : Endpoint.forRouteId(route.getId());
        if (endpoint != null
                && exactRoute(exchange, route, endpoint)
                && verifiedIdentity(exchange)) {
            exchange.getAttributes().put(
                    FORWARDING_ATTRIBUTE,
                    new TrustedForward(
                            FORWARDING_CAPABILITY,
                            route.getId(),
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getURI().getPath(),
                            route.getUri(),
                            exchange.getRequest().getHeaders()
                                    .getFirst(VerifiedIdentityFilter.USER_HEADER),
                            exchange.getRequest().getHeaders()
                                    .getFirst(VerifiedIdentityFilter.TENANT_HEADER)));
        }
        return chain.filter(exchange);
    }

    static boolean permits(ServerRequest request, Endpoint expected) {
        Object value = request.exchange().getAttribute(FORWARDING_ATTRIBUTE);
        if (!(value instanceof TrustedForward trusted)
                || trusted.capability() != FORWARDING_CAPABILITY) {
            return false;
        }
        return trusted.routeId().equals(expected.routeId)
                && trusted.originalMethod() == expected.method
                && trusted.originalPath().equals(expected.externalPath)
                && trusted.forwardUri().equals(expected.forwardUri())
                && request.method() == expected.method
                && request.path().equals(expected.handlerPath)
                && trusted.actorId().equals(request.headers()
                        .firstHeader(VerifiedIdentityFilter.USER_HEADER))
                && trusted.tenantId().equals(request.headers()
                        .firstHeader(VerifiedIdentityFilter.TENANT_HEADER));
    }

    private boolean exactRoute(
            ServerWebExchange exchange,
            Route route,
            Endpoint endpoint) {
        return exchange.getRequest().getMethod() == endpoint.method
                && exchange.getRequest().getURI().getPath().equals(endpoint.externalPath)
                && route.getUri().equals(endpoint.forwardUri());
    }

    private boolean verifiedIdentity(ServerWebExchange exchange) {
        String actorId = exchange.getRequest().getHeaders()
                .getFirst(VerifiedIdentityFilter.USER_HEADER);
        String tenantId = exchange.getRequest().getHeaders()
                .getFirst(VerifiedIdentityFilter.TENANT_HEADER);
        return "USER".equals(exchange.getAttribute(ApiHistoryAttributes.ACTOR_TYPE))
                && positive(actorId)
                && positive(tenantId)
                && Objects.equals(actorId, stringAttribute(
                        exchange, ApiHistoryAttributes.ACTOR_ID))
                && Objects.equals(tenantId, stringAttribute(
                        exchange, ApiHistoryAttributes.TENANT_ID));
    }

    private String stringAttribute(ServerWebExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private boolean positive(String value) {
        try {
            return value != null && Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        // Run after identity/support/provider-plane enforcement and before gateway routing.
        return -80;
    }

    enum Endpoint {
        CONTEXTS(
                "product-surface-contexts",
                HttpMethod.GET,
                "/api/auth/product-surface-contexts",
                ProductSurfaceGatewayRoutes.CONTEXTS_HANDLER_PATH),
        PRODUCT_EVALUATION(
                "product-surface-access-evaluation",
                HttpMethod.POST,
                "/api/auth/product-surface-access/evaluate",
                ProductSurfaceGatewayRoutes.PRODUCT_EVALUATION_HANDLER_PATH),
        GOVERNED_EVALUATION(
                "governed-route-access-evaluation",
                HttpMethod.POST,
                "/api/auth/governed-route-access/evaluate",
                ProductSurfaceGatewayRoutes.GOVERNED_EVALUATION_HANDLER_PATH);

        private final String routeId;
        private final HttpMethod method;
        private final String externalPath;
        private final String handlerPath;

        Endpoint(
                String routeId,
                HttpMethod method,
                String externalPath,
                String handlerPath) {
            this.routeId = routeId;
            this.method = method;
            this.externalPath = externalPath;
            this.handlerPath = handlerPath;
        }

        URI forwardUri() {
            return URI.create("forward:" + handlerPath);
        }

        String routeId() {
            return routeId;
        }

        HttpMethod method() {
            return method;
        }

        String externalPath() {
            return externalPath;
        }

        String handlerPath() {
            return handlerPath;
        }

        static Endpoint forRouteId(String routeId) {
            return Arrays.stream(values())
                    .filter(endpoint -> endpoint.routeId.equals(routeId))
                    .findFirst()
                    .orElse(null);
        }
    }

    private record TrustedForward(
            Object capability,
            String routeId,
            HttpMethod originalMethod,
            String originalPath,
            URI forwardUri,
            String actorId,
            String tenantId) {
    }
}
