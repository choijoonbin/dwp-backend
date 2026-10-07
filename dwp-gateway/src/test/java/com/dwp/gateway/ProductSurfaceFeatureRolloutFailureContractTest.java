package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.FeatureRolloutEvaluationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceFeatureRolloutFailureContractTest
        extends ProductSurfaceFeatureRolloutTestSupport {

    @Test
    void approvalRequestsFailClosedWithoutVerifiedTenantOrRolloutAuthority() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange missingTenant = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/approvals/v1/home")
                        .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "111")
                        .build());
        AtomicBoolean missingTenantForwarded = new AtomicBoolean();

        filter.filter(missingTenant, ignored -> {
            missingTenantForwarded.set(true);
            return Mono.empty();
        }).block();

        when(client.evaluateProducts(eq(7L), eq(List.of("approvals")), any()))
                .thenReturn(Mono.error(new FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException()));
        MockServerWebExchange unavailable = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/approvals/v1/home")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .build());
        AtomicBoolean unavailableForwarded = new AtomicBoolean();

        filter.filter(unavailable, ignored -> {
            unavailableForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(missingTenant.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missingTenantForwarded).isFalse();
        assertThat(unavailable.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailableForwarded).isFalse();
    }

    @Test
    void telemetryRejectsDuplicateProductKeysBeforeRolloutEvaluation() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/platform/v1/observability/product-surface-events")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .body("{\"schemaVersion\":1,\"productKey\":\"hcm\","
                                + "\"productKey\":\"approvals\"}"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(client, org.mockito.Mockito.never())
                .evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void telemetryReturnsServiceUnavailableWhenTheDurableLatchCannotBeRead() {
        FeatureRolloutEvaluationClient client = mock(FeatureRolloutEvaluationClient.class);
        when(client.evaluateProducts(eq(7L), eq(List.of("hcm")), any()))
                .thenReturn(Mono.error(new FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException()));
        ProductSurfaceRolloutHeaderFilter filter =
                new ProductSurfaceRolloutHeaderFilter(
                        client, productRouteCatalog(), new ObjectMapper());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/platform/v1/observability/product-surface-events")
                        .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                        .body("{\"schemaVersion\":1,\"eventName\":\"surface.exposed\","
                                + "\"productKey\":\"hcm\"}"));
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(forwarded).isFalse();
    }
}
