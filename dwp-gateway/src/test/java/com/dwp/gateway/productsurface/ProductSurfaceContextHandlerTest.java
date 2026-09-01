package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.observability.api.ApiHistoryAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class ProductSurfaceContextHandlerTest {

    private final ProductSurfaceContextAggregationService aggregation =
            mock(ProductSurfaceContextAggregationService.class);

    private WebTestClient client;
    private WebTestClient directClient;

    @BeforeEach
    void setUp() {
        ProductSurfaceContextHandler handler = new ProductSurfaceContextHandler(
                aggregation, new ObjectMapper().findAndRegisterModules());
        var routes = new ProductSurfaceContextRouterConfiguration()
                .productSurfaceContextRoutes(handler);
        Map<String, Object> trustedMarkers = Arrays.stream(
                        ProductSurfaceForwardingGuardFilter.Endpoint.values())
                .collect(Collectors.toUnmodifiableMap(
                        ProductSurfaceForwardingGuardFilter.Endpoint::handlerPath,
                        this::trustedMarker));
        client = WebTestClient.bindToRouterFunction(routes)
                .webFilter((exchange, chain) -> {
                    Object marker = trustedMarkers.get(exchange.getRequest().getURI().getPath());
                    if (marker != null) {
                        exchange.getAttributes().put(
                                ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE,
                                marker);
                    }
                    return chain.filter(exchange);
                })
                .build();
        directClient = WebTestClient.bindToRouterFunction(routes).build();
    }

    @Test
    void returnsTheAuthenticatedContextEnvelope() {
        when(aggregation.listContexts(any())).thenReturn(Mono.just(
                new ProductSurfaceContextDtos.ContextListData(
                        "1",
                        "psr-1",
                        new ProductSurfaceContextDtos.SourceRevisions(
                                "auth-1", "policy-1", null, null, null),
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        OffsetDateTime.parse("2026-08-24T01:00:00Z"),
                        List.of(),
                        List.of())));

        client.get().uri(ProductSurfaceContextRouterConfiguration.CONTEXTS_HANDLER_PATH)
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.contractVersion").isEqualTo("1")
                .jsonPath("$.data.activeAccessMode").isEqualTo("NORMAL");
    }

    @Test
    void rejectsCrossSubjectInputsBeforeAuthorityEvaluation() {
        String body = """
                {
                  "subject": {
                    "type": "GOVERNED_CONTEXT",
                    "productKey": "approvals",
                    "surfaceKey": "approvals.admin"
                  },
                  "navigationContextId": "work.work",
                  "routeContractKey": "route.context.work__work.review-detail.data"
                }
                """;

        client.post().uri(ProductSurfaceContextRouterConfiguration.GOVERNED_EVALUATION_HANDLER_PATH)
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("INVALID_REQUEST");

        verify(aggregation, never()).evaluateGoverned(any(), any());
    }

    @Test
    void rejectsUnknownCrossUnionAndDuplicateFieldsBeforeAuthorityEvaluation() {
        List<String> payloads = List.of(
                """
                {"subject":{"type":"PRODUCT","productKey":"approvals",\
                "surfaceKey":"approvals.admin"},"routeContractKey":"route.test",\
                "navigationContextId":"work.work"}
                """,
                """
                {"subject":{"type":"GOVERNED_CONTEXT","productKey":null},\
                "navigationContextId":"work.work","routeContractKey":"route.test"}
                """,
                """
                {"subject":{"type":"PRODUCT","productKey":"approvals",\
                "surfaceKey":"approvals.admin"},"routeContractKey":"route.first",\
                "routeContractKey":"route.second"}
                """);

        client.post().uri(ProductSurfaceContextRouterConfiguration.PRODUCT_EVALUATION_HANDLER_PATH)
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payloads.get(0))
                .exchange().expectStatus().isBadRequest();
        client.post().uri(ProductSurfaceContextRouterConfiguration.GOVERNED_EVALUATION_HANDLER_PATH)
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payloads.get(1))
                .exchange().expectStatus().isBadRequest();
        client.post().uri(ProductSurfaceContextRouterConfiguration.PRODUCT_EVALUATION_HANDLER_PATH)
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payloads.get(2))
                .exchange().expectStatus().isBadRequest();

        verify(aggregation, never()).evaluateProduct(any(), any());
        verify(aggregation, never()).evaluateGoverned(any(), any());
    }

    @Test
    void exposesAuthorityOutagesAs503() {
        when(aggregation.listContexts(any())).thenReturn(Mono.error(
                new ProductSurfaceAuthorityUnavailableException()));

        client.get().uri(ProductSurfaceContextRouterConfiguration.CONTEXTS_HANDLER_PATH)
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.errorCode")
                .isEqualTo("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void refusesAForwardWhoseIdentityNoLongerMatchesItsMarker() {
        client.get().uri(ProductSurfaceContextRouterConfiguration.CONTEXTS_HANDLER_PATH)
                .exchange()
                .expectStatus().isNotFound();

        verify(aggregation, never()).listContexts(any());
    }

    @Test
    void refusesDirectInternalUrisEvenWithSpoofedIdentityAndMarkerHeaders() {
        directClient.get().uri(ProductSurfaceContextRouterConfiguration.CONTEXTS_HANDLER_PATH)
                .headers(this::spoofInternalHeaders)
                .exchange()
                .expectStatus().isNotFound();
        directClient.post()
                .uri(ProductSurfaceContextRouterConfiguration.PRODUCT_EVALUATION_HANDLER_PATH)
                .headers(this::spoofInternalHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound();
        directClient.post()
                .uri(ProductSurfaceContextRouterConfiguration.GOVERNED_EVALUATION_HANDLER_PATH)
                .headers(this::spoofInternalHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound();

        verify(aggregation, never()).listContexts(any());
        verify(aggregation, never()).evaluateProduct(any(), any());
        verify(aggregation, never()).evaluateGoverned(any(), any());
    }

    private Object trustedMarker(ProductSurfaceForwardingGuardFilter.Endpoint endpoint) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(endpoint.method(), endpoint.externalPath())
                .header(VerifiedIdentityFilter.USER_HEADER, "7")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .build());
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_TYPE, "USER");
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, "7");
        exchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, "1");
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()
                .id(endpoint.routeId())
                .uri(endpoint.forwardUri())
                .predicate(ignored -> true)
                .build());
        AtomicReference<Object> marker = new AtomicReference<>();

        new ProductSurfaceForwardingGuardFilter().filter(exchange, forwarded -> {
            marker.set(forwarded.getAttribute(
                    ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE));
            return Mono.empty();
        }).block();

        return Objects.requireNonNull(marker.get());
    }

    private void spoofInternalHeaders(org.springframework.http.HttpHeaders headers) {
        headers.set(VerifiedIdentityFilter.USER_HEADER, "7");
        headers.set(VerifiedIdentityFilter.TENANT_HEADER, "1");
        headers.set(ProductSurfaceForwardingGuardFilter.FORWARDING_ATTRIBUTE, "spoofed");
    }
}
