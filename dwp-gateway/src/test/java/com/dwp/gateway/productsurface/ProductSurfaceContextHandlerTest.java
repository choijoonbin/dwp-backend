package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceContextHandlerTest {

    private final ProductSurfaceContextAggregationService aggregation =
            mock(ProductSurfaceContextAggregationService.class);

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ProductSurfaceContextHandler handler = new ProductSurfaceContextHandler(
                aggregation, new ObjectMapper().findAndRegisterModules());
        var routes = new ProductSurfaceContextRouterConfiguration()
                .productSurfaceContextRoutes(handler);
        client = WebTestClient.bindToRouterFunction(routes).build();
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
                new ProductSurfaceContextAggregationService.AuthorityUnavailableException()));

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
    void refusesMissingVerifiedIdentityHeaders() {
        client.get().uri(ProductSurfaceContextRouterConfiguration.CONTEXTS_HANDLER_PATH)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("AUTHENTICATION_REQUIRED");
    }
}
