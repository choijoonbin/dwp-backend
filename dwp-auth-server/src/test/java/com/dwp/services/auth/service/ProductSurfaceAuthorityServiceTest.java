package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductSurfaceAuthorityServiceTest {

    private final OffsetDateTime now =
            OffsetDateTime.of(2026, 8, 24, 1, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void delegatesToTheSingleRegistryAdapter() {
        ProductSurfaceAuthorityPort port = ignored -> allowed();
        ProductSurfaceAuthorityService service = service(Stream.of(port));

        var result = service.evaluate(request("route.approvals.admin.forms.page"));

        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.routeGrantRef()).isEqualTo("grant-ref-1");
        assertThat(result.scopes()).singleElement()
                .extracting(ProductSurfaceAuthorityDtos.EffectiveScope::isDefault)
                .isEqualTo(true);
    }

    @Test
    void failsClosedWhenTheRegistryAdapterIsMissing() {
        ProductSurfaceAuthorityService service = service(Stream.empty());

        var result = service.evaluate(request(null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void rejectsAnAllowedResultWithMismatchedSurfaceOrNoRouteGrant() {
        ProductSurfaceAuthorityPort port = ignored -> new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.ALLOWED,
                null,
                "auth-1",
                "policy-1",
                "context-1",
                "approvals",
                "approvals.other",
                "management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                "APP.APPROVALS",
                allowed().effectiveGrants(),
                allowed().scopes(),
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                now.plusMinutes(5),
                null);

        var result = service(Stream.of(port)).evaluate(
                request("route.approvals.admin.forms.page"));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void rejectsADenialWithoutSourceRevisions() {
        ProductSurfaceAuthorityPort port = ignored -> new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                "ROUTE_CAPABILITY_REQUIRED",
                null,
                null,
                null,
                "approvals",
                "approvals.admin",
                null,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
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

        var result = service(Stream.of(port)).evaluate(request(null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void preservesAClosedStepUpRequiredDirectRouteDecision() {
        ProductSurfaceAuthorityDtos.AuthorityResult base = allowed();
        ProductSurfaceAuthorityPort port = ignored -> new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED,
                "STEP_UP_REQUIRED",
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
                base.scopes(),
                base.routeGrantRef(),
                base.effectiveReadOnly(),
                base.requiresProductEligibility(),
                base.validUntil(),
                base.expiredAt(),
                "urn:dwp:acr:mfa",
                "approval-high-risk-v1",
                base.revalidateAt(),
                base.evidenceRef());

        var result = service(Stream.of(port)).evaluate(
                request("route.approvals.admin.forms.page"));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(result.effectiveGrants()).isNotEmpty();
        assertThat(result.scopes()).isNotEmpty();
        assertThat(result.requiredAssurance()).isEqualTo("urn:dwp:acr:mfa");
    }

    private ProductSurfaceAuthorityService service(Stream<ProductSurfaceAuthorityPort> ports) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceAuthorityPort> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(ports);
        return new ProductSurfaceAuthorityService(provider);
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request(String routeContractKey) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                1L,
                7L,
                "approvals",
                "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                routeContractKey,
                null,
                null,
                null,
                null,
                List.of());
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult allowed() {
        OffsetDateTime expires = now.plusHours(1);
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.ALLOWED,
                null,
                "auth-1",
                "policy-1",
                "context-1",
                "approvals",
                "approvals.admin",
                "management",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                "APP.APPROVALS",
                List.of(new ProductSurfaceAuthorityDtos.CapabilityGrant(
                        "approvals.design.read",
                        "ADMIN.APPROVAL_DESIGN:VIEW",
                        ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.PERMISSION,
                        List.of(),
                        ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                        new ProductSurfaceAuthorityDtos.Responsibility(
                                "APP_CONFIG_ADMIN", "APPROVALS"),
                        List.of("scope-1"),
                        false,
                        false,
                        ProductSurfaceAuthorityDtos.ActivationState.ACTIVE,
                        expires)),
                List.of(new ProductSurfaceAuthorityDtos.EffectiveScope(
                        "scope-1", "RESOURCE_SET", "Approvals", true, false, expires)),
                "grant-ref-1",
                false,
                false,
                expires,
                null,
                null,
                null,
                expires,
                "evidence-1");
    }
}
