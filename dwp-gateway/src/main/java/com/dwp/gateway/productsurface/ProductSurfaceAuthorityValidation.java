package com.dwp.gateway.productsurface;

import java.util.Set;

import static com.dwp.gateway.productsurface.ProductSurfaceContextAggregationSupport.blank;

/** Validates authority replies before they participate in an aggregated decision. */
final class ProductSurfaceAuthorityValidation {

    private ProductSurfaceAuthorityValidation() {
    }

    static boolean validAuthority(
            ProductSurfaceContextDtos.AuthorityResult authority,
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.ProductCandidate candidate,
            String routeContractKey) {
        if (authority == null || authority.decision() == null
                || authority.ownerReceiptAuthority() != null
                || authority.accessMode() != requestContext.activeAccessMode()
                || !candidate.productKey().equals(authority.productKey())
                || !candidate.surfaceKey().equals(authority.surfaceKey())) {
            return false;
        }
        if (blank(authority.authRevision()) || blank(authority.policyRevision())
                || blank(authority.tenantControlRevision())) return false;
        if (authority.decision() != ProductSurfaceContextDtos.Decision.ALLOWED) {
            if (authority.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
                return authority.accessSource() != null
                        && !blank(authority.appResourceKey())
                        && !authority.effectiveGrants().isEmpty()
                        && !authority.scopes().isEmpty()
                        && ProductSurfaceScopeIntersection.closed(
                                authority.effectiveGrants(), authority.scopes())
                        && !blank(authority.requiredAssurance())
                        && !blank(authority.requestPolicyRef());
            }
            return authority.effectiveGrants().isEmpty() && authority.scopes().isEmpty();
        }
        if (!Set.of("work", "management").contains(authority.plane())) return false;
        long defaults = authority.scopes().stream()
                .filter(ProductSurfaceContextDtos.EffectiveScope::isDefault)
                .count();
        return (authority.scopes().size() != 1 || defaults == 1)
                && !blank(authority.contextKey())
                && authority.accessSource() != null
                && !blank(authority.appResourceKey())
                && !authority.effectiveGrants().isEmpty()
                && !authority.scopes().isEmpty()
                && ProductSurfaceScopeIntersection.closed(
                        authority.effectiveGrants(), authority.scopes())
                && authority.revalidateAt() != null
                && (blank(routeContractKey) || !blank(authority.routeGrantRef()));
    }

    static boolean canonicalMarker(String value) {
        return !blank(value) && value.equals(value.strip()) && value.indexOf(',') < 0
                && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8_192
                && value.chars().noneMatch(c -> c < 0x20 || c == 0x7f)
                && value.split("\\.", -1).length == 3;
    }

    static boolean validGoverned(
            ProductSurfaceContextDtos.GovernedAuthorityResult result,
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.GovernedEvaluationRequest request) {
        if (result == null || result.decision() == null
                || result.accessMode() != requestContext.activeAccessMode()
                || !request.navigationContextId().equals(result.navigationContextId())) {
            return false;
        }
        if (blank(result.authRevision()) || blank(result.policyRevision())) return false;
        if (result.decision() != ProductSurfaceContextDtos.GovernedDecision.ALLOWED) return true;
        return !blank(result.contextKey())
                && result.accessSource() != null
                && !blank(result.routeGrantRef())
                && result.revalidateAt() != null;
    }

    static boolean validEligibility(ProductSurfaceContextDtos.EligibilityResult result) {
        if (result == null || result.decision() == null
                || !Set.of(
                                ProductSurfaceContextDtos.Decision.ALLOWED,
                                ProductSurfaceContextDtos.Decision.SURFACE_DENIED,
                                ProductSurfaceContextDtos.Decision.SCOPE_INVALID,
                                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE)
                        .contains(result.decision())
                || blank(result.productRelationshipRevision())
                || blank(result.targetPopulationRevision())) {
            return false;
        }
        if (result.decision() != ProductSurfaceContextDtos.Decision.ALLOWED) {
            return result.scopes().isEmpty();
        }
        if (result.scopes().isEmpty() || result.revalidateAt() == null) return false;
        Set<String> sourceKeys = new java.util.HashSet<>();
        if (result.scopes().stream().anyMatch(scope -> scope == null
                || blank(scope.sourceScopeKey()) || blank(scope.key())
                || blank(scope.kind()) || blank(scope.displayName())
                || !sourceKeys.add(scope.sourceScopeKey()))) return false;
        long distinctScopes = result.scopes().stream()
                .map(ProductSurfaceContextDtos.EligibleScope::key)
                .distinct()
                .count();
        long defaults = result.scopes().stream()
                .filter(ProductSurfaceContextDtos.EligibleScope::isDefault)
                .map(ProductSurfaceContextDtos.EligibleScope::key)
                .distinct()
                .count();
        return defaults <= 1 && (distinctScopes != 1 || defaults == 1);
    }

}
