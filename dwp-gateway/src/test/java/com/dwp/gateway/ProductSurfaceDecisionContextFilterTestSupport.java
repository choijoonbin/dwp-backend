package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

abstract class ProductSurfaceDecisionContextFilterTestSupport {

    protected static final String REVISION = "psr-" + "a".repeat(64);
    protected static final OffsetDateTime REVALIDATE_AT =
            OffsetDateTime.parse("2036-08-24T00:00:00Z");
    protected static final String HCM_DERIVED_SCOPE = "hcm-scope-" + "c".repeat(40);
    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
            objectMapper,
            new ClassPathResource("product-authorization/product-surfaces-v1.generated.json"));

    protected ProductSurfaceDecisionContextFilter filter(
            ProductSurfaceContextAggregationService authority) {
        return new ProductSurfaceDecisionContextFilter(authority, catalog, objectMapper);
    }

    protected ProductSurfaceContextAggregationService authority(
            ProductSurfaceContextAggregationService.TrustedProductEvaluation result) {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        when(authority.evaluateProductTrusted(any(), any())).thenReturn(Mono.just(result));
        return authority;
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation allowed() {
        ProductSurfaceContextDtos.EffectiveScope scope = scope();
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        "ctx-approval", "approvals", "approvals.work", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.ENTITLEMENT,
                        "APP.APPROVALS", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-1", scope, false, REVALIDATE_AT, null,
                        null, null, REVALIDATE_AT),
                "ctx-approval", scope, sourceRevisions(), false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedHcm() {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        HCM_DERIVED_SCOPE, "SELF", "Self", true, false, REVALIDATE_AT);
        String contextKey = "psc-" + "b".repeat(64);
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        contextKey, "hcm", "hcm.personal", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.RELATIONSHIP,
                        "APP.HCM", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-hcm-services", scope, false, REVALIDATE_AT,
                        null, null, null, REVALIDATE_AT),
                contextKey, scope, sourceRevisions(), false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedDwaion() {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        "scope-dwaion-self", "SELF", "Self", true, false, REVALIDATE_AT);
        String contextKey = "psc-" + "c".repeat(64);
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        contextKey, "dwaion", "dwaion.work", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.ENTITLEMENT,
                        "APP.ASK", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-dwaion", scope, false, REVALIDATE_AT,
                        null, null, null, REVALIDATE_AT),
                contextKey, scope, sourceRevisions(), false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedWave2(
            String scopeKind) {
        return allowedWave2(scopeKind, scopeKind);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedWave2(
            String scopeKind,
            String displayName) {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        HCM_DERIVED_SCOPE, scopeKind, displayName, true, false, REVALIDATE_AT);
        String contextKey = "psc-" + "d".repeat(64);
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        contextKey, "hcm", "hcm.operations", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.RELATIONSHIP,
                        "APP.HCM", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-hcm-wave2", scope, false, REVALIDATE_AT,
                        null, null, null, REVALIDATE_AT),
                contextKey,
                scope,
                sourceRevisions(),
                false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation stepUp() {
        ProductSurfaceContextDtos.EffectiveScope scope = scope();
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED,
                        "STEP_UP_REQUIRED", REVISION, null, null, null, null,
                        REVALIDATE_AT, null, "urn:dwp:acr:mfa",
                        "STEPUP-MGMT-HIGH-V1", REVALIDATE_AT),
                "ctx-approval", scope, sourceRevisions(), false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation stepUpWave2(
            String scopeKind) {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        HCM_DERIVED_SCOPE, scopeKind, scopeKind, true, false, REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED,
                        "STEP_UP_REQUIRED", REVISION, null, null, null, null,
                        REVALIDATE_AT, null, "urn:dwp:acr:mfa",
                        "STEPUP-MGMT-HIGH-V1", REVALIDATE_AT),
                "psc-" + "d".repeat(64),
                scope,
                sourceRevisions(),
                false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation routeNotRegistered() {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                        "ROUTE_NOT_REGISTERED", REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
                sourceRevisions(),
                false);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation productNotRegistered() {
        return productNotRegistered(true);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation productNotRegistered(
            boolean authRouteProductNotRegistered) {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                        "PRODUCT_NOT_REGISTERED", REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
                sourceRevisions(),
                authRouteProductNotRegistered);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation activationRequired(
            String reasonCode) {
        return deniedEvaluation(ProductSurfaceContextDtos.Decision.ACTIVATION_REQUIRED, reasonCode);
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation authorityUnavailable() {
        return deniedEvaluation(
                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE,
                "AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    protected ProductSurfaceContextAggregationService.TrustedProductEvaluation deniedEvaluation(
            ProductSurfaceContextDtos.Decision decision,
            String reasonCode) {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        decision, reasonCode, REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
                sourceRevisions(),
                false);
    }

    protected ProductSurfaceContextDtos.SourceRevisions sourceRevisions() {
        return new ProductSurfaceContextDtos.SourceRevisions(
                "auth-v38",
                "policy-v38",
                "relationship-v1",
                "population-v1",
                "tenant-control-activation-1",
                null);
    }

    protected ProductSurfaceContextDtos.EffectiveScope scope() {
        return new ProductSurfaceContextDtos.EffectiveScope(
                "scope-approval", "APP_RESOURCE_SET", "Approvals", true, false,
                REVALIDATE_AT);
    }

    protected MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request
                .header("X-Correlation-ID", "corr-1").build());
    }

    protected String body(MockServerWebExchange exchange) {
        DataBuffer buffer = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        if (buffer == null) return "";
        byte[] bytes = new byte[buffer.readableByteCount()];
        try {
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
