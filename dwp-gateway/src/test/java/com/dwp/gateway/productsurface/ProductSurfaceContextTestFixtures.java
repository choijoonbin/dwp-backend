package com.dwp.gateway.productsurface;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Canonical product-surface DTO fixtures shared by focused aggregation tests.
 *
 * <p>Keeping these constructors separate from scenario assertions makes contract changes visible
 * in one place without hiding behavior behind mocks or an application test context.</p>
 */
final class ProductSurfaceContextTestFixtures {

    private static final OffsetDateTime VALID_UNTIL =
            OffsetDateTime.parse("2026-08-24T02:00:00Z");
    private static final OffsetDateTime REVALIDATE_AT =
            OffsetDateTime.parse("2026-08-24T01:10:00Z");

    private ProductSurfaceContextTestFixtures() {
    }

    static ProductSurfaceContextDtos.RequestContext requestContext() {
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

    static ProductSurfaceContextDtos.ProductEvaluationRequest evaluationRequest() {
        return new ProductSurfaceContextDtos.ProductEvaluationRequest(
                new ProductSurfaceContextDtos.Subject(
                        "PRODUCT", "approvals", "approvals.admin"),
                "route.approvals.admin.forms.page",
                null,
                null);
    }

    static ProductSurfaceContextDtos.AuthorityResult allowed(boolean requiresEligibility) {
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
                        VALID_UNTIL)),
                List.of(new ProductSurfaceContextDtos.EffectiveScope(
                        "scope-1", "RESOURCE_SET", "Approvals", true, false, VALID_UNTIL)),
                "route-grant-1",
                false,
                requiresEligibility,
                VALID_UNTIL,
                null,
                null,
                null,
                REVALIDATE_AT,
                "auth-evidence");
    }

    static ProductSurfaceContextDtos.AuthorityResult allowed(
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

    static ProductSurfaceContextDtos.AuthorityResult allowedWithAppResourceKey(String key) {
        ProductSurfaceContextDtos.AuthorityResult base = allowed(false);
        return new ProductSurfaceContextDtos.AuthorityResult(
                base.decision(), base.reasonCode(), base.authRevision(), base.policyRevision(),
                base.contextKey(), base.productKey(), base.surfaceKey(), base.plane(),
                base.accessMode(), base.accessSource(), key, base.effectiveGrants(), base.scopes(),
                base.routeGrantRef(), base.effectiveReadOnly(), base.requiresProductEligibility(),
                base.validUntil(), base.expiredAt(), base.requiredAssurance(),
                base.requestPolicyRef(), base.revalidateAt(), base.evidenceRef());
    }

    static ProductSurfaceContextDtos.AuthorityResult challenged(boolean requiresEligibility) {
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
                        VALID_UNTIL)),
                List.of(scope("scope-1", true)),
                null,
                true,
                requiresEligibility,
                VALID_UNTIL,
                null,
                "urn:dwp:acr:mfa",
                "STEPUP-MGMT-HIGH-V1",
                null,
                "auth-evidence");
    }

    static ProductSurfaceContextDtos.AuthorityResult denied() {
        return denied("approvals", "approvals.admin", "SURFACE_DENIED");
    }

    static ProductSurfaceContextDtos.AuthorityResult surfaceNotRegistered(
            String productKey,
            String surfaceKey) {
        return denied(productKey, surfaceKey, "SURFACE_NOT_REGISTERED");
    }

    static ProductSurfaceContextDtos.AuthorityResult productNotRegistered(
            String productKey,
            String surfaceKey) {
        return denied(productKey, surfaceKey, "PRODUCT_NOT_REGISTERED");
    }

    static ProductSurfaceContextDtos.AuthorityResult denied(
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
                REVALIDATE_AT,
                "auth-evidence");
    }

    static ProductSurfaceContextDtos.ProductRollout rollout(String state) {
        return rollout("approvals", state);
    }

    static ProductSurfaceContextDtos.ProductRollout rollout(
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

    static ProductSurfaceContextDtos.AuthorityResult allowedWithScopes(
            List<ProductSurfaceContextDtos.EffectiveScope> scopes) {
        return allowedWithScopes(scopes, false);
    }

    static ProductSurfaceContextDtos.AuthorityResult allowedWithScopes(
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

    static ProductSurfaceContextDtos.EffectiveScope scope(String key, boolean isDefault) {
        return new ProductSurfaceContextDtos.EffectiveScope(
                key,
                "RESOURCE_SET",
                key,
                isDefault,
                false,
                VALID_UNTIL);
    }

    static ProductSurfaceContextDtos.EligibleScope eligibleScope(
            String sourceScopeKey, String key, boolean isDefault, boolean readOnly) {
        return new ProductSurfaceContextDtos.EligibleScope(
                sourceScopeKey, key, "RESOURCE_SET", key, isDefault, readOnly, VALID_UNTIL);
    }

    static ProductSurfaceContextDtos.AuthorityResult unavailable() {
        return unavailable("approvals", "approvals.admin");
    }

    static ProductSurfaceContextDtos.AuthorityResult unavailable(
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
