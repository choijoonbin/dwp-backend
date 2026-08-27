package com.dwp.gateway.productsurface;

import com.dwp.gateway.filter.ProviderDataPlaneBoundaryFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.VerifiedIdentity;
import com.dwp.observability.api.ApiHistoryAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class ProductSurfaceForwardingGuardFilterTest {

    private final ProductSurfaceForwardingGuardFilter guard =
            new ProductSurfaceForwardingGuardFilter();

    @Test
    void issuesHandlerBoundCapabilitiesForTheThreeExactVerifiedTenantRoutes() {
        for (ProductSurfaceForwardingGuardFilter.Endpoint endpoint
                : ProductSurfaceForwardingGuardFilter.Endpoint.values()) {
            MockServerWebExchange external = externalExchange(endpoint);
            AtomicReference<ServerWebExchange> marked = new AtomicReference<>();
            VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.just(
                    new VerifiedIdentity("7", "1", List.of("TENANT_ADMIN"), "TENANT")));

            identity.filter(external, verified -> new ProviderDataPlaneBoundaryFilter().filter(
                    verified,
                    permitted -> guard.filter(permitted, forwarded -> {
                        marked.set(forwarded);
                        return Mono.empty();
                    }))).block();

            assertThat(marked.get()).as(endpoint.name()).isNotNull();
            Object capability = marked.get().getAttribute(
                    ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE);
            assertThat(capability)
                    .as(endpoint.name())
                    .isNotNull();

            ServerWebExchange internal = marked.get().mutate()
                    .request(marked.get().getRequest().mutate()
                            .path(endpoint.handlerPath())
                            .build())
                    .build();
            ServerRequest handlerRequest = ServerRequest.create(
                    internal, HandlerStrategies.withDefaults().messageReaders());

            assertThat(ProductSurfaceForwardingGuardFilter.permits(
                    handlerRequest, endpoint)).as(endpoint.name()).isTrue();
        }
    }

    @Test
    void refusesHeaderSpoofingWithoutVerifiedExchangeIdentity() {
        ProductSurfaceForwardingGuardFilter.Endpoint endpoint =
                ProductSurfaceForwardingGuardFilter.Endpoint.CONTEXTS;
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(endpoint.method(), endpoint.externalPath())
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .header(ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE, "spoofed")
                .build());
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route(endpoint));

        guard.filter(exchange, ignored -> Mono.empty()).block();

        Object capability = exchange.getAttribute(
                ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE);
        assertThat(capability).isNull();
    }

    @Test
    void refusesNearMissRouteMethodPathForwardTargetOrIdentity() {
        ProductSurfaceForwardingGuardFilter.Endpoint endpoint =
                ProductSurfaceForwardingGuardFilter.Endpoint.CONTEXTS;

        assertUnmarked(endpoint, HttpMethod.POST, endpoint.externalPath(),
                endpoint.routeId(), endpoint.forwardUri(), "7", "1");
        assertUnmarked(endpoint, endpoint.method(), endpoint.externalPath() + "/child",
                endpoint.routeId(), endpoint.forwardUri(), "7", "1");
        assertUnmarked(endpoint, endpoint.method(), endpoint.externalPath(),
                "auth-server", endpoint.forwardUri(), "7", "1");
        assertUnmarked(endpoint, endpoint.method(), endpoint.externalPath(),
                endpoint.routeId(), URI.create("forward:/_gateway/fallback"), "7", "1");
        assertUnmarked(endpoint, endpoint.method(), endpoint.externalPath(),
                endpoint.routeId(), endpoint.forwardUri(), "8", "1");
        assertUnmarked(endpoint, endpoint.method(), endpoint.externalPath(),
                endpoint.routeId(), endpoint.forwardUri(), "7", "2");
    }

    @Test
    void capabilityCannotBeReplayedForAnotherHandlerOrIdentity() {
        ProductSurfaceForwardingGuardFilter.Endpoint endpoint =
                ProductSurfaceForwardingGuardFilter.Endpoint.CONTEXTS;
        MockServerWebExchange marked = verifiedExchange(endpoint);

        ServerWebExchange wrongHandler = marked.mutate()
                .request(marked.getRequest().mutate()
                        .path(ProductSurfaceContextRouterConfiguration
                                .PRODUCT_EVALUATION_HANDLER_PATH)
                        .build())
                .build();
        ServerRequest wrongHandlerRequest = ServerRequest.create(
                wrongHandler, HandlerStrategies.withDefaults().messageReaders());
        assertThat(ProductSurfaceForwardingGuardFilter.permits(
                wrongHandlerRequest, endpoint)).isFalse();

        ServerWebExchange wrongIdentity = marked.mutate()
                .request(marked.getRequest().mutate()
                        .path(endpoint.handlerPath())
                        .headers(headers -> headers.set(
                                VerifiedIdentityFilter.TENANT_HEADER, "2"))
                        .build())
                .build();
        ServerRequest wrongIdentityRequest = ServerRequest.create(
                wrongIdentity, HandlerStrategies.withDefaults().messageReaders());
        assertThat(ProductSurfaceForwardingGuardFilter.permits(
                wrongIdentityRequest, endpoint)).isFalse();
    }

    private MockServerWebExchange verifiedExchange(
            ProductSurfaceForwardingGuardFilter.Endpoint endpoint) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(endpoint.method(), endpoint.externalPath())
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .build());
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route(endpoint));
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_TYPE, "USER");
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, "7");
        exchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, "1");
        guard.filter(exchange, ignored -> Mono.empty()).block();
        return exchange;
    }

    private void assertUnmarked(
            ProductSurfaceForwardingGuardFilter.Endpoint endpoint,
            HttpMethod method,
            String path,
            String routeId,
            URI forwardUri,
            String headerActorId,
            String headerTenantId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(method, path)
                .header(VerifiedIdentityFilter.USER_HEADER, headerActorId)
                .header(VerifiedIdentityFilter.TENANT_HEADER, headerTenantId)
                .build());
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()
                .id(routeId)
                .uri(forwardUri)
                .predicate(ignored -> true)
                .build());
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_TYPE, "USER");
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, "7");
        exchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, "1");

        guard.filter(exchange, ignored -> Mono.empty()).block();

        Object capability = exchange.getAttribute(
                ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE);
        assertThat(capability)
                .as("%s %s", method, path)
                .isNull();
    }

    private MockServerWebExchange externalExchange(
            ProductSurfaceForwardingGuardFilter.Endpoint endpoint) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(endpoint.method(), endpoint.externalPath())
                .header(VerifiedIdentityFilter.USER_HEADER, "spoofed")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "spoofed")
                .build());
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route(endpoint));
        return exchange;
    }

    private Route route(ProductSurfaceForwardingGuardFilter.Endpoint endpoint) {
        return Route.async()
                .id(endpoint.routeId())
                .uri(endpoint.forwardUri())
                .predicate(ignored -> true)
                .build();
    }
}
