package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceContractEligibilityIntegrationTest {

    private static final Set<String> PILOT_PRODUCTS =
            Set.of("approvals", "communications", "hcm", "services");

    @Test
    void activeV2PointerFailsClosedForRaw110FutureAndInventoryOnlyProducts() {
        GeneratedProductSurfaceCandidateCatalog catalog =
                new GeneratedProductSurfaceCandidateCatalog(
                        new ObjectMapper(),
                        new ClassPathResource(
                                "product-authorization/product-surfaces-v1.generated.json"),
                        new ClassPathResource(
                                "product-authorization/product-surfaces-v1.index.generated.json"),
                        new ClassPathResource(
                                "product-authorization/"
                                        + "product-surface-rollout-inventory.v1.generated.json"));
        ProductSurfaceAuthorityClient authority = mock(ProductSurfaceAuthorityClient.class);
        GovernedRouteAuthorityClient governed = mock(GovernedRouteAuthorityClient.class);
        ProductSurfaceEligibilityClient eligibility = mock(ProductSurfaceEligibilityClient.class);
        FeatureRolloutEvaluationClient rollout = mock(FeatureRolloutEvaluationClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceCandidateCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenReturn(Stream.of(catalog));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(catalog.rolloutProductKeys().stream()
                        .map(this::enforcedRollout)
                        .toList()));
        ProductSurfaceContextAggregationService service =
                new ProductSurfaceContextAggregationService(
                        authority,
                        governed,
                        eligibility,
                        rollout,
                        catalogs,
                        Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.listContexts(
                        new ProductSurfaceContextDtos.RequestContext(
                                1L,
                                7L,
                                ProductSurfaceContextDtos.AccessMode.NORMAL,
                                null,
                                null,
                                List.of(),
                                "corr-contract-eligibility",
                                null,
                                null))
                .block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);

        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void productScopedEnforcementRunsFourExactPilotsAlongsideSevenCompatibilityProducts() {
        GeneratedProductSurfaceCandidateCatalog catalog = generatedCatalog();
        ProductSurfaceAuthorityClient authority = mock(ProductSurfaceAuthorityClient.class);
        GovernedRouteAuthorityClient governed = mock(GovernedRouteAuthorityClient.class);
        ProductSurfaceEligibilityClient eligibility = mock(ProductSurfaceEligibilityClient.class);
        FeatureRolloutEvaluationClient rollout = mock(FeatureRolloutEvaluationClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceCandidateCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenReturn(Stream.of(catalog));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(catalog.rolloutProductKeys().stream()
                        .map(product -> rollout(
                                product, PILOT_PRODUCTS.contains(product) ? "111" : "100"))
                        .toList()));
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Mono.just(denied(
                        invocation.getArgument(1), invocation.getArgument(2))));
        ProductSurfaceContextAggregationService service =
                new ProductSurfaceContextAggregationService(
                        authority,
                        governed,
                        eligibility,
                        rollout,
                        catalogs,
                        Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).isEmpty();
        assertThat(result.rollouts()).hasSize(11);
        assertThat(result.rollouts()).allSatisfy(value -> {
            boolean pilot = PILOT_PRODUCTS.contains(value.productKey());
            assertThat(value.state()).isEqualTo(pilot ? "111" : "100");
            assertThat(value.authorityStatus()).isEqualTo(pilot
                    ? ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE
                    : ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
        });
        assertThat(catalog.activeCandidates().stream()
                        .map(ProductSurfaceContextDtos.ProductCandidate::productKey)
                        .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(PILOT_PRODUCTS);
    }

    private GeneratedProductSurfaceCandidateCatalog generatedCatalog() {
        return new GeneratedProductSurfaceCandidateCatalog(
                new ObjectMapper(),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.generated.json"),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.index.generated.json"),
                new ClassPathResource(
                        "product-authorization/"
                                + "product-surface-rollout-inventory.v1.generated.json"));
    }

    private ProductSurfaceContextDtos.RequestContext requestContext() {
        return new ProductSurfaceContextDtos.RequestContext(
                1L,
                7L,
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                null,
                null,
                List.of(),
                "corr-contract-eligibility",
                null,
                null);
    }

    private ProductSurfaceContextDtos.AuthorityResult denied(
            String productKey,
            String surfaceKey) {
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.SURFACE_DENIED,
                "SURFACE_DENIED",
                "auth-4",
                "policy-8",
                null,
                productKey,
                surfaceKey,
                null,
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                null,
                null,
                List.of(),
                List.of(),
                null,
                true,
                false,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2026-08-24T01:10:00Z"),
                "auth-evidence");
    }

    private ProductSurfaceContextDtos.ProductRollout rollout(
            String productKey,
            String state) {
        boolean shadow = state.charAt(0) == '1';
        boolean enforcement = state.charAt(1) == '1';
        boolean ui = state.charAt(2) == '1';
        return new ProductSurfaceContextDtos.ProductRollout(
                productKey,
                state,
                new ProductSurfaceContextDtos.RolloutFlags(shadow, enforcement, ui),
                ui ? "full" : "baseline",
                "rollout-contract-eligibility-" + productKey,
                ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
    }

    private ProductSurfaceContextDtos.ProductRollout enforcedRollout(String productKey) {
        return new ProductSurfaceContextDtos.ProductRollout(
                productKey,
                "110",
                new ProductSurfaceContextDtos.RolloutFlags(true, true, false),
                "baseline",
                "rollout-contract-eligibility",
                ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
    }
}
