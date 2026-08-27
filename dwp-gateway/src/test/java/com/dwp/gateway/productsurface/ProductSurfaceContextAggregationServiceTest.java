package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceContextAggregationServiceTest {

    private final ProductSurfaceAuthorityClient authority =
            mock(ProductSurfaceAuthorityClient.class);
    private final GovernedRouteAuthorityClient governed =
            mock(GovernedRouteAuthorityClient.class);
    private final ProductSurfaceEligibilityClient eligibility =
            mock(ProductSurfaceEligibilityClient.class);
    private final FeatureRolloutEvaluationClient rollout =
            mock(FeatureRolloutEvaluationClient.class);
    private final ProductSurfaceCandidateCatalog catalog =
            mock(ProductSurfaceCandidateCatalog.class);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC);

    private ProductSurfaceContextAggregationService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceCandidateCatalog> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(catalog));
        when(catalog.activeCandidates()).thenReturn(List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin")));
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(new ProductSurfaceContextDtos.ProductRollout(
                        "approvals",
                        "110",
                        new ProductSurfaceContextDtos.RolloutFlags(true, true, false),
                        "baseline",
                        "rollout-1",
                        ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED))));
        service = new ProductSurfaceContextAggregationService(
                authority, governed, eligibility, rollout, provider, clock);
    }

    @Test
    void buildsAStableContextListFromTheRegistryCandidate() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowed(false)));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contractVersion()).isEqualTo("1");
        assertThat(result.generatedAt()).isEqualTo("2026-08-24T01:00Z");
        assertThat(result.decisionRevision()).startsWith("psr-");
        assertThat(result.contexts()).singleElement()
                .extracting(ProductSurfaceContextDtos.EffectiveContext::surfaceKey)
                .isEqualTo("approvals.admin");
        assertThat(result.rollouts()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("110");
            assertThat(value.authorityStatus()).isEqualTo(
                    ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE);
        });
        verify(catalog, times(1)).activeCandidates();
        verify(rollout).evaluateProducts(eq(1L), eq(List.of("approvals")), any());
    }

    @Test
    void deniedActorStillReceivesTheCanonicalEnforcementRollout() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(denied()));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(new ProductSurfaceContextDtos.ProductRollout(
                        "approvals",
                        "111",
                        new ProductSurfaceContextDtos.RolloutFlags(true, true, true),
                        "enforcement",
                        "rollout-2",
                        ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED))));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).isEmpty();
        assertThat(result.rollouts()).singleElement().satisfies(value -> {
            assertThat(value.productKey()).isEqualTo("approvals");
            assertThat(value.state()).isEqualTo("111");
            assertThat(value.flags().capabilityEnforcement()).isTrue();
            assertThat(value.authorityStatus()).isEqualTo(
                    ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE);
        });
        verify(catalog, times(1)).activeCandidates();
        verify(rollout).evaluateProducts(eq(1L), eq(List.of("approvals")), any());
    }

    @Test
    void duplicateCanonicalCandidatesFailBeforeAuthorityOrRolloutEvaluation() {
        var candidate = new ProductSurfaceContextDtos.ProductCandidate(
                "approvals", "approvals.admin");
        when(catalog.activeCandidates()).thenReturn(List.of(candidate, candidate));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);

        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
        verify(rollout, never()).evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void intersectsProductEligibilityAndUsesItsEarlierRevalidation() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowed(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.ALLOWED,
                        null,
                        "relationship-7",
                        "population-3",
                        List.of(new ProductSurfaceContextDtos.EligibleScope(
                                "scope-1", "team-1", "TEAM", "My team", true, true,
                                OffsetDateTime.parse("2026-08-24T01:20:00Z"))),
                        OffsetDateTime.parse("2026-08-24T01:05:00Z"),
                        "people-evidence")));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.sourceRevisions().productRelationship())
                .isEqualTo("relationship-7");
        assertThat(result.contexts().getFirst().revalidateAt())
                .isEqualTo("2026-08-24T01:05Z");
        assertThat(result.contexts().getFirst().scopes().getFirst().kind()).isEqualTo("TEAM");
        assertThat(result.contexts().getFirst().effectiveGrants().getFirst().scopeKeys())
                .containsExactly("team-1");
        assertThat(result.contexts().getFirst().scopes().getFirst().readOnly()).isTrue();
    }

    @Test
    void rejectsPeopleScopeExpansionWithoutAuthScopeProvenance() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowed(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.ALLOWED,
                        null,
                        "relationship-7",
                        "population-3",
                        List.of(eligibleScope("unmapped-auth-scope", "team-1", true, false)),
                        OffsetDateTime.parse("2026-08-24T01:05:00Z"),
                        "people-evidence")));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void rejectsAmbiguousEligibilityDefaults() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("scope-1", true), scope("scope-2", false)), true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.ALLOWED,
                        null,
                        "relationship-7",
                        "population-3",
                        List.of(
                                eligibleScope("scope-1", "team-1", true, false),
                                eligibleScope("scope-2", "team-2", true, false)),
                        OffsetDateTime.parse("2026-08-24T01:05:00Z"),
                        "people-evidence")));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void ownerProductNotRegisteredReasonCannotDowngradeAuthParticipatingProduct() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowed(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.SURFACE_DENIED,
                        "PRODUCT_NOT_REGISTERED",
                        "relationship-7",
                        "population-3",
                        List.of(),
                        null,
                        "people-evidence")));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).isEmpty();
        assertThat(result.rollouts()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("110");
            assertThat(value.flags().capabilityEnforcement()).isTrue();
            assertThat(value.authorityStatus())
                    .isEqualTo(ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE);
        });
    }

    @Test
    void foreignEligibilityDecisionCannotFabricateDirectProductAbsence() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowed(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                        "PRODUCT_NOT_REGISTERED",
                        "relationship-7",
                        "population-3",
                        List.of(),
                        null,
                        "people-evidence")));

        assertThatThrownBy(() -> service.evaluateProductTrusted(
                        requestContext(), evaluationRequest()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void failsClosedWhenAuthorityResolutionIsUnavailable() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(unavailable()));
        var request = new ProductSurfaceContextDtos.ProductEvaluationRequest(
                new ProductSurfaceContextDtos.Subject(
                        "PRODUCT", "approvals", "approvals.admin"),
                "route.approvals.admin.forms.page",
                null,
                null);

        assertThatThrownBy(() -> service.evaluateProduct(requestContext(), request).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void failsClosedWhenAuthorityReturnsAnEmptyResponse() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
        var request = new ProductSurfaceContextDtos.ProductEvaluationRequest(
                new ProductSurfaceContextDtos.Subject(
                        "PRODUCT", "approvals", "approvals.admin"),
                "route.approvals.admin.forms.page",
                null,
                null);

        assertThatThrownBy(() -> service.evaluateProduct(requestContext(), request).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void failsClosedWhenEligibilityOmitsItsSourceRevisions() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowed(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.ALLOWED,
                        null,
                        null,
                        null,
                        List.of(new ProductSurfaceContextDtos.EligibleScope(
                                "scope-1", "team-1", "TEAM", "My team", true, false,
                                OffsetDateTime.parse("2026-08-24T01:20:00Z"))),
                        OffsetDateTime.parse("2026-08-24T01:05:00Z"),
                        null)));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void failsClosedWhenTheDurableRolloutLatchIsUnavailable() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(allowed(false)));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.error(new FeatureRolloutEvaluationClient
                        .RolloutAuthorityUnavailableException()));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void noActiveAuthorityBundleAllowsOnlyTheConfirmedOffBaseline() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(unavailable()));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(rollout("000"))));

        var baseline = service.listContexts(requestContext()).block();

        assertThat(baseline).isNotNull();
        assertThat(baseline.contexts()).isEmpty();
        assertThat(baseline.rollouts()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("000");
            assertThat(value.authorityStatus()).isEqualTo(
                    ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
        });
        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void defaultOffEnvelopeIncludesTheCompleteRolloutInventoryWithoutRouteAuthority() {
        List<String> products = List.of(
                "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
                "meetings", "messaging", "notifications", "services", "spaces", "workplace");
        when(catalog.rolloutProductKeys()).thenReturn(products);
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(products.stream()
                        .map(product -> rollout(product, "000"))
                        .toList()));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).isEmpty();
        assertThat(result.rollouts())
                .extracting(ProductSurfaceContextDtos.ProductRollout::productKey)
                .containsExactlyElementsOf(products);
        assertThat(result.rollouts())
                .allMatch(value -> value.authorityStatus()
                        == ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
        verify(rollout).evaluateProducts(eq(1L), eq(products), any());
        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void contractLessProductsFailClosedWhenRawRolloutRequiresEnforcement() {
        List<String> products = List.of(
                "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
                "meetings", "messaging", "notifications", "services", "spaces", "workplace");
        List<ProductSurfaceContextDtos.ProductCandidate> active = List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "communications", "communications.admin"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "hcm", "hcm.management"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "services", "services.admin"));
        when(catalog.activeCandidates()).thenReturn(active);
        when(catalog.rolloutProductKeys()).thenReturn(products);
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(products.stream()
                        .map(product -> rollout(product, "110"))
                        .toList()));
        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);

        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void contractLessRaw100ProductRemainsShadowCompatibilityOnly() {
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals", "hcm"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        rollout("approvals", "100"),
                        rollout("hcm", "100"))));
        when(authority.evaluate(any(), eq("approvals"), eq("approvals.admin"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(allowed(false)));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).singleElement()
                .extracting(ProductSurfaceContextDtos.EffectiveContext::productKey)
                .isEqualTo("approvals");
        assertThat(result.rollouts()).filteredOn(value -> "hcm".equals(value.productKey()))
                .singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("100");
            assertThat(value.authorityStatus()).isEqualTo(
                    ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
        });
        verify(authority, times(1)).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void uiEnabledProductWithoutActiveAuthorityFailsClosed() {
        List<String> products = List.of(
                "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
                "meetings", "messaging", "notifications", "services", "spaces", "workplace");
        when(catalog.rolloutProductKeys()).thenReturn(products);
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(products.stream()
                        .map(product -> rollout(product,
                                "calendar".equals(product) ? "111" : "110"))
                        .toList()));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);

        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void raw110ProductMissingFromTheActiveBundleFailsClosed() {
        when(catalog.activeCandidates()).thenReturn(List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "hcm", "hcm.management")));
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals", "hcm"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        rollout("approvals", "110"),
                        rollout("hcm", "110"))));
        when(authority.evaluate(any(), eq("approvals"), eq("approvals.admin"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(allowed(false)));
        when(authority.evaluate(any(), eq("hcm"), eq("hcm.management"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(
                        productNotRegistered("hcm", "hcm.management")));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void participatingProductSurfaceDriftNeverDowngradesEnforcement() {
        when(catalog.activeCandidates()).thenReturn(List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin")));
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(rollout("approvals", "110"))));
        when(authority.evaluate(any(), eq("approvals"), eq("approvals.admin"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(
                        surfaceNotRegistered("approvals", "approvals.admin")));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).isEmpty();
        assertThat(result.rollouts()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("110");
            assertThat(value.flags().capabilityEnforcement()).isTrue();
            assertThat(value.authorityStatus())
                    .isEqualTo(ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE);
        });
    }

    @Test
    void noActiveAuthorityBundleFailsClosedWhenEnforcementWasApproved() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(unavailable()));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void shadowAuthorityFailureKeepsBaselineAndReportsPerProductUnavailable() {
        when(authority.evaluate(any(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(Mono.just(unavailable()));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(rollout("100"))));

        var shadow = service.listContexts(requestContext()).block();

        assertThat(shadow).isNotNull();
        assertThat(shadow.contexts()).isEmpty();
        assertThat(shadow.rollouts()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo("100");
            assertThat(value.authorityStatus()).isEqualTo(
                    ProductSurfaceContextDtos.AuthorityStatus.UNAVAILABLE);
        });
    }

    @Test
    void mixedAuthorityAvailabilityKeepsOneShadowFailureIsolatedPerProduct() {
        when(catalog.activeCandidates()).thenReturn(List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "hcm", "hcm.management")));
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals", "hcm"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        rollout("approvals", "100"),
                        rollout("hcm", "100"))));
        when(authority.evaluate(any(), eq("approvals"), eq("approvals.admin"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(allowed(false)));
        when(authority.evaluate(any(), eq("hcm"), eq("hcm.management"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(unavailable(
                        "hcm", "hcm.management")));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.contexts()).singleElement()
                .extracting(ProductSurfaceContextDtos.EffectiveContext::productKey)
                .isEqualTo("approvals");
        assertThat(result.rollouts()).extracting(
                        ProductSurfaceContextDtos.ProductRollout::productKey,
                        ProductSurfaceContextDtos.ProductRollout::authorityStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "approvals",
                                ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple(
                                "hcm",
                                ProductSurfaceContextDtos.AuthorityStatus.UNAVAILABLE));
        verify(catalog, times(1)).activeCandidates();
        verify(rollout).evaluateProducts(
                eq(1L), eq(List.of("approvals", "hcm")), any());
    }

    @Test
    void inconsistentGlobalShadowAxisFailsBeforeAuthorityResolution() {
        when(catalog.activeCandidates()).thenReturn(List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "hcm", "hcm.management")));
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals", "hcm"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        rollout("approvals", "000"),
                        rollout("hcm", "110"))));

        assertThatThrownBy(() -> service.listContexts(requestContext()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);

        verify(authority, never()).evaluate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void productScopedEnforcementAxesMayDifferWithinOneContextEnvelope() {
        when(catalog.activeCandidates()).thenReturn(List.of(
                new ProductSurfaceContextDtos.ProductCandidate(
                        "approvals", "approvals.admin"),
                new ProductSurfaceContextDtos.ProductCandidate(
                        "hcm", "hcm.management")));
        when(catalog.rolloutProductKeys()).thenReturn(List.of("approvals", "hcm"));
        when(rollout.evaluateProducts(anyLong(), any(), any()))
                .thenReturn(Mono.just(List.of(
                        rollout("approvals", "100"),
                        rollout("hcm", "110"))));
        when(authority.evaluate(any(), eq("approvals"), eq("approvals.admin"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(allowed(false)));
        when(authority.evaluate(any(), eq("hcm"), eq("hcm.management"),
                isNull(), isNull(), isNull())).thenReturn(Mono.just(
                        allowed("hcm", "hcm.management")));

        var result = service.listContexts(requestContext()).block();

        assertThat(result).isNotNull();
        assertThat(result.rollouts()).extracting(
                        ProductSurfaceContextDtos.ProductRollout::productKey,
                        ProductSurfaceContextDtos.ProductRollout::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("approvals", "100"),
                        org.assertj.core.groups.Tuple.tuple("hcm", "110"));
        assertThat(result.contexts()).hasSize(2);
    }

    @Test
    void reportsAMissingCandidateCatalogThroughTheReactiveFailurePath() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceCandidateCatalog> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        ProductSurfaceContextAggregationService noCatalogService =
                new ProductSurfaceContextAggregationService(
                        authority, governed, eligibility, rollout, provider, clock);

        Mono<ProductSurfaceContextDtos.ContextListData> result =
                noCatalogService.listContexts(requestContext());

        assertThatThrownBy(result::block)
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void producesAGovernedContextWithoutAProductSurface() {
        when(governed.evaluate(any(), any())).thenReturn(Mono.just(
                new ProductSurfaceContextDtos.GovernedAuthorityResult(
                        ProductSurfaceContextDtos.GovernedDecision.ALLOWED,
                        null,
                        "auth-4",
                        "policy-8",
                        "governed-1",
                        "work.work",
                        ProductSurfaceContextDtos.AccessSource.RELATIONSHIP,
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        "review-grant",
                        false,
                        OffsetDateTime.parse("2026-08-24T02:00:00Z"),
                        null,
                        null,
                        null,
                        OffsetDateTime.parse("2026-08-24T01:05:00Z"),
                        "review-evidence")));
        var request = new ProductSurfaceContextDtos.GovernedEvaluationRequest(
                new ProductSurfaceContextDtos.Subject(
                        "GOVERNED_CONTEXT", null, null),
                "work.work",
                "route.context.work__work.review-detail.data",
                new ProductSurfaceContextDtos.GovernedTarget("item-1", "v11"),
                null);

        var result = service.evaluateGoverned(requestContext(), request).block();

        assertThat(result).isNotNull();
        assertThat(result.context().navigationContextId()).isEqualTo("work.work");
        assertThat(result.context().decisionRevision()).startsWith("psr-");
    }

    @Test
    void directEvaluationAutomaticallySelectsExactlyOneDefaultScope() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("scope-1", false),
                        scope("scope-2", true),
                        scope("scope-3", false)))));

        var result = service.evaluateProduct(requestContext(), evaluationRequest()).block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(ProductSurfaceContextDtos.Decision.ALLOWED);
        assertThat(result.scope().key()).isEqualTo("scope-2");
        verify(rollout, never()).evaluateProducts(anyLong(), any(), any());
    }

    @Test
    void failsClosedWhenAllowedAuthorityOmitsTheCanonicalAppResourceKey() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithAppResourceKey(null)));

        assertThatThrownBy(() -> service.evaluateProduct(
                requestContext(), evaluationRequest()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void evaluatesPeopleEligibilityBeforeReturningStepUpRequired() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(challenged(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.ALLOWED,
                        "ALLOWED",
                        "relationship-9",
                        "population-4",
                        List.of(eligibleScope("scope-1", "scope-1", true, false)),
                        OffsetDateTime.parse("2026-08-24T01:05:00Z"),
                        "people-evidence")));

        var result = service.evaluateProduct(requestContext(), evaluationRequest()).block();

        assertThat(result).isNotNull();
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED);
        assertThat(result.requestPolicyRef()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(result.context()).isNull();
        verify(eligibility).evaluate(any(), any(), isNull(), any());
    }

    @Test
    void eligibilityDenialSuppressesTheStepUpChallenge() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(challenged(true)));
        when(eligibility.evaluate(any(), any(), isNull(), any()))
                .thenReturn(Mono.just(new ProductSurfaceContextDtos.EligibilityResult(
                        ProductSurfaceContextDtos.Decision.SURFACE_DENIED,
                        "TARGET_POPULATION_REQUIRED",
                        "relationship-9",
                        "population-4",
                        List.of(),
                        null,
                        "people-evidence")));

        var result = service.evaluateProduct(requestContext(), evaluationRequest()).block();

        assertThat(result).isNotNull();
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceContextDtos.Decision.SURFACE_DENIED);
        assertThat(result.reasonCode()).isEqualTo("TARGET_POPULATION_REQUIRED");
        assertThat(result.requiredAssurance()).isNull();
        assertThat(result.requestPolicyRef()).isNull();
        verify(eligibility).evaluate(any(), any(), isNull(), any());
    }

    @Test
    void directEvaluationRequiresSelectionWhenMultipleScopesHaveNoDefault() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("scope-1", false),
                        scope("scope-2", false)))));

        var result = service.evaluateProduct(requestContext(), evaluationRequest()).block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(
                ProductSurfaceContextDtos.Decision.SCOPE_SELECTION_REQUIRED);
        assertThat(result.scope()).isNull();
        assertThat(result.context()).isNull();
    }

    @Test
    void suppliedInvalidScopeNeverFallsBackToAnAuthorityDefault() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("scope-1", true),
                        scope("scope-2", false)))));
        var request = new ProductSurfaceContextDtos.ProductEvaluationRequest(
                evaluationRequest().subject(), evaluationRequest().routeContractKey(),
                null, "scope-revoked");

        var result = service.evaluateProduct(requestContext(), request).block();

        assertThat(result).isNotNull();
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceContextDtos.Decision.SCOPE_INVALID);
        assertThat(result.reasonCode()).isEqualTo("SCOPE_CONTEXT_EXPIRED");
        assertThat(result.scope()).isNull();
        assertThat(result.context()).isNull();
    }

    @Test
    void aSingleScopeWithoutTheRequiredDefaultMarkerFailsClosed() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("scope-1", false)))));

        assertThatThrownBy(() -> service.evaluateProduct(
                requestContext(), evaluationRequest()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void failsClosedWhenAnAuthGrantReferencesAScopeOutsideTheFinalClosure() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("unbound-scope", true)))));

        assertThatThrownBy(() -> service.evaluateProduct(
                requestContext(), evaluationRequest()).block())
                .isInstanceOf(ProductSurfaceContextAggregationService
                        .AuthorityUnavailableException.class);
    }

    @Test
    void directEvaluationRequiresSelectionWhenMultipleScopesClaimDefault() {
        when(authority.evaluate(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(allowedWithScopes(List.of(
                        scope("scope-1", true),
                        scope("scope-2", true)))));

        var result = service.evaluateProduct(requestContext(), evaluationRequest()).block();

        assertThat(result).isNotNull();
        assertThat(result.decision()).isEqualTo(
                ProductSurfaceContextDtos.Decision.SCOPE_SELECTION_REQUIRED);
        assertThat(result.scope()).isNull();
        assertThat(result.context()).isNull();
    }

    private ProductSurfaceContextDtos.RequestContext requestContext() {
        return new ProductSurfaceContextDtos.RequestContext(
                1L,
                7L,
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                null,
                null,
                List.of(),
                "corr-1",
                null,
                null);
    }

    private ProductSurfaceContextDtos.ProductEvaluationRequest evaluationRequest() {
        return new ProductSurfaceContextDtos.ProductEvaluationRequest(
                new ProductSurfaceContextDtos.Subject(
                        "PRODUCT", "approvals", "approvals.admin"),
                "route.approvals.admin.forms.page",
                null,
                null);
    }

    private ProductSurfaceContextDtos.AuthorityResult allowed(boolean requiresEligibility) {
        OffsetDateTime expires = OffsetDateTime.parse("2026-08-24T02:00:00Z");
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.ALLOWED,
                null,
                "auth-4",
                "policy-8",
                "context-1",
                "approvals",
                "approvals.admin",
                "management",
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                ProductSurfaceContextDtos.AccessSource.MANAGEMENT,
                "APP.APPROVALS",
                List.of(new ProductSurfaceContextDtos.CapabilityGrant(
                        "approvals.design.read",
                        "ADMIN.APPROVAL_DESIGN:VIEW",
                        ProductSurfaceContextDtos.CapabilityAuthorityMode.PERMISSION,
                        List.of(),
                        "REQUIRED",
                        new ProductSurfaceContextDtos.Responsibility(
                                "APP_CONFIG_ADMIN", "APPROVALS"),
                        List.of("scope-1"),
                        false,
                        false,
                        "ACTIVE",
                        expires)),
                List.of(new ProductSurfaceContextDtos.EffectiveScope(
                        "scope-1", "RESOURCE_SET", "Approvals", true, false, expires)),
                "route-grant-1",
                false,
                requiresEligibility,
                expires,
                null,
                null,
                null,
                OffsetDateTime.parse("2026-08-24T01:10:00Z"),
                "auth-evidence");
    }

    private ProductSurfaceContextDtos.AuthorityResult allowed(
            String productKey,
            String surfaceKey) {
        ProductSurfaceContextDtos.AuthorityResult base = allowed(false);
        return new ProductSurfaceContextDtos.AuthorityResult(
                base.decision(), base.reasonCode(), base.authRevision(), base.policyRevision(),
                base.contextKey(), productKey, surfaceKey, base.plane(), base.accessMode(),
                base.accessSource(), base.appResourceKey(), base.effectiveGrants(), base.scopes(),
                base.routeGrantRef(), base.effectiveReadOnly(), base.requiresProductEligibility(),
                base.validUntil(), base.expiredAt(), base.requiredAssurance(),
                base.requestPolicyRef(), base.revalidateAt(), base.evidenceRef());
    }

    private ProductSurfaceContextDtos.AuthorityResult allowedWithAppResourceKey(String key) {
        ProductSurfaceContextDtos.AuthorityResult base = allowed(false);
        return new ProductSurfaceContextDtos.AuthorityResult(
                base.decision(), base.reasonCode(), base.authRevision(), base.policyRevision(),
                base.contextKey(), base.productKey(), base.surfaceKey(), base.plane(),
                base.accessMode(), base.accessSource(), key, base.effectiveGrants(), base.scopes(),
                base.routeGrantRef(), base.effectiveReadOnly(), base.requiresProductEligibility(),
                base.validUntil(), base.expiredAt(), base.requiredAssurance(),
                base.requestPolicyRef(), base.revalidateAt(), base.evidenceRef());
    }

    private ProductSurfaceContextDtos.AuthorityResult challenged(boolean requiresEligibility) {
        OffsetDateTime expires = OffsetDateTime.parse("2026-08-24T02:00:00Z");
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED,
                "STEP_UP_REQUIRED",
                "auth-4",
                "policy-8",
                null,
                "approvals",
                "approvals.admin",
                null,
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                ProductSurfaceContextDtos.AccessSource.MANAGEMENT,
                "ADMIN.APPROVAL_DESIGN",
                List.of(new ProductSurfaceContextDtos.CapabilityGrant(
                        "approvals.design.publish",
                        "ADMIN.APPROVAL_DESIGN:PUBLISH",
                        ProductSurfaceContextDtos.CapabilityAuthorityMode.PERMISSION,
                        List.of("predicate.people.object-version.v1"),
                        "REQUIRED",
                        new ProductSurfaceContextDtos.Responsibility(
                                "APP_CONFIG_ADMIN", "RS_APPROVALS"),
                        List.of("scope-1"),
                        false,
                        false,
                        "ELIGIBLE",
                        expires)),
                List.of(scope("scope-1", true)),
                null,
                true,
                requiresEligibility,
                expires,
                null,
                "urn:dwp:acr:mfa",
                "STEPUP-MGMT-HIGH-V1",
                null,
                "auth-evidence");
    }

    private ProductSurfaceContextDtos.AuthorityResult denied() {
        return denied("approvals", "approvals.admin", "SURFACE_DENIED");
    }

    private ProductSurfaceContextDtos.AuthorityResult surfaceNotRegistered(
            String productKey,
            String surfaceKey) {
        return denied(productKey, surfaceKey, "SURFACE_NOT_REGISTERED");
    }

    private ProductSurfaceContextDtos.AuthorityResult productNotRegistered(
            String productKey,
            String surfaceKey) {
        return denied(productKey, surfaceKey, "PRODUCT_NOT_REGISTERED");
    }

    private ProductSurfaceContextDtos.AuthorityResult denied(
            String productKey,
            String surfaceKey,
            String reasonCode) {
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.SURFACE_DENIED,
                reasonCode,
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

    private ProductSurfaceContextDtos.ProductRollout rollout(String state) {
        return rollout("approvals", state);
    }

    private ProductSurfaceContextDtos.ProductRollout rollout(
            String productKey,
            String state) {
        return new ProductSurfaceContextDtos.ProductRollout(
                productKey,
                state,
                new ProductSurfaceContextDtos.RolloutFlags(
                        state.charAt(0) == '1',
                        state.charAt(1) == '1',
                        state.charAt(2) == '1'),
                "baseline",
                "rollout-test",
                ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED);
    }

    private ProductSurfaceContextDtos.AuthorityResult allowedWithScopes(
            List<ProductSurfaceContextDtos.EffectiveScope> scopes) {
        return allowedWithScopes(scopes, false);
    }

    private ProductSurfaceContextDtos.AuthorityResult allowedWithScopes(
            List<ProductSurfaceContextDtos.EffectiveScope> scopes,
            boolean requiresEligibility) {
        ProductSurfaceContextDtos.AuthorityResult base = allowed(requiresEligibility);
        return new ProductSurfaceContextDtos.AuthorityResult(
                base.decision(),
                base.reasonCode(),
                base.authRevision(),
                base.policyRevision(),
                base.contextKey(),
                base.productKey(),
                base.surfaceKey(),
                base.plane(),
                base.accessMode(),
                base.accessSource(),
                base.appResourceKey(),
                base.effectiveGrants(),
                scopes,
                base.routeGrantRef(),
                base.effectiveReadOnly(),
                base.requiresProductEligibility(),
                base.validUntil(),
                base.expiredAt(),
                base.requiredAssurance(),
                base.requestPolicyRef(),
                base.revalidateAt(),
                base.evidenceRef());
    }

    private ProductSurfaceContextDtos.EffectiveScope scope(String key, boolean isDefault) {
        return new ProductSurfaceContextDtos.EffectiveScope(
                key,
                "RESOURCE_SET",
                key,
                isDefault,
                false,
                OffsetDateTime.parse("2026-08-24T02:00:00Z"));
    }

    private ProductSurfaceContextDtos.EligibleScope eligibleScope(
            String sourceScopeKey, String key, boolean isDefault, boolean readOnly) {
        return new ProductSurfaceContextDtos.EligibleScope(
                sourceScopeKey, key, "RESOURCE_SET", key, isDefault, readOnly,
                OffsetDateTime.parse("2026-08-24T02:00:00Z"));
    }

    private ProductSurfaceContextDtos.AuthorityResult unavailable() {
        return unavailable("approvals", "approvals.admin");
    }

    private ProductSurfaceContextDtos.AuthorityResult unavailable(
            String productKey,
            String surfaceKey) {
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE,
                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                null,
                null,
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
                null,
                null);
    }
}
