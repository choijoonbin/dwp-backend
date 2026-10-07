package com.dwp.gateway.productsurface;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Monotonic Auth-scope to People-derived-scope intersection. */
final class ProductSurfaceScopeIntersection {

    private ProductSurfaceScopeIntersection() {
    }

    static Intersection intersect(
            List<ProductSurfaceContextDtos.EffectiveGrant> grants,
            List<ProductSurfaceContextDtos.EffectiveScope> authorityScopes,
            List<ProductSurfaceContextDtos.EligibleScope> eligibleScopes) {
        Map<String, ProductSurfaceContextDtos.EffectiveScope> sourceScopes;
        try {
            sourceScopes = authorityScopes.stream().collect(Collectors.toUnmodifiableMap(
                    ProductSurfaceContextDtos.EffectiveScope::key,
                    Function.identity()));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        List<ProductSurfaceContextDtos.EffectiveGrant> rebound = grants.stream()
                .map(grant -> rebind(grant, eligibleScopes))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (rebound.isEmpty()) throw unavailable();
        Set<String> usedScopeKeys = rebound.stream()
                .flatMap(grant -> grant.scopeKeys().stream())
                .collect(Collectors.toUnmodifiableSet());
        Map<String, DerivedAlias> derivedScopes =
                new LinkedHashMap<>();
        for (ProductSurfaceContextDtos.EligibleScope eligible : eligibleScopes) {
            DerivedAlias derived =
                    derivedScope(sourceScopes, eligible);
            if (!usedScopeKeys.contains(derived.scope().key())) continue;
            DerivedAlias existing =
                    derivedScopes.putIfAbsent(derived.scope().key(), derived);
            if (existing != null) {
                derivedScopes.put(derived.scope().key(), mergeAlias(existing, derived));
            }
        }
        List<ProductSurfaceContextDtos.EffectiveScope> scopes =
                derivedScopes.values().stream().map(DerivedAlias::scope).toList();
        if (scopes.isEmpty()) throw unavailable();
        return new Intersection(rebound, scopes);
    }

    /**
     * Multiple Auth resolver aliases may intentionally name the same owner population. The
     * derived key binds the owner material, while read-only and expiry constraints remain
     * grant-specific. Collapse only aliases with the same semantic identity, taking the most
     * restrictive expiry and leaving scope mutability enabled only when at least one surviving
     * grant can mutate it. Grant-level read-only flags continue to constrain each route.
     */
    private static DerivedAlias mergeAlias(
            DerivedAlias left,
            DerivedAlias right) {
        ProductSurfaceContextDtos.EffectiveScope leftScope = left.scope();
        ProductSurfaceContextDtos.EffectiveScope rightScope = right.scope();
        if (!leftScope.key().equals(rightScope.key())
                || !leftScope.kind().equals(rightScope.kind())
                || !java.util.Objects.equals(
                        leftScope.displayName(), rightScope.displayName())
                || leftScope.isDefault() != rightScope.isDefault()
                // An owner-service read-only difference is not an Auth alias difference and
                // cannot be weakened by another source alias.
                || left.ownerReadOnly() != right.ownerReadOnly()) {
            throw unavailable();
        }
        return new DerivedAlias(new ProductSurfaceContextDtos.EffectiveScope(
                leftScope.key(), leftScope.kind(), leftScope.displayName(),
                leftScope.isDefault(),
                leftScope.readOnly() && rightScope.readOnly(),
                earliest(leftScope.validUntil(), rightScope.validUntil())),
                left.ownerReadOnly());
    }

    private static DerivedAlias derivedScope(
            Map<String, ProductSurfaceContextDtos.EffectiveScope> sources,
            ProductSurfaceContextDtos.EligibleScope eligible) {
        ProductSurfaceContextDtos.EffectiveScope source =
                sources.get(eligible.sourceScopeKey());
        if (source == null || blank(eligible.key()) || blank(eligible.kind())) {
            throw unavailable();
        }
        return new DerivedAlias(new ProductSurfaceContextDtos.EffectiveScope(
                    eligible.key(), eligible.kind(), eligible.displayName(),
                    eligible.isDefault(), source.readOnly() || eligible.readOnly(),
                    earliest(source.validUntil(), eligible.validUntil())),
                eligible.readOnly());
    }

    private static ProductSurfaceContextDtos.EffectiveGrant rebind(
            ProductSurfaceContextDtos.EffectiveGrant grant,
            List<ProductSurfaceContextDtos.EligibleScope> eligibleScopes) {
        List<String> keys = eligibleScopes.stream()
                .filter(scope -> grant.scopeKeys().contains(scope.sourceScopeKey()))
                .map(ProductSurfaceContextDtos.EligibleScope::key)
                .distinct()
                .toList();
        // Eligibility is an intersection, not an all-or-nothing assertion over a mixed Auth
        // context. A surface may combine People-owned scopes with route-only Auth/Platform
        // scopes. Unmatched grants are removed here; direct routes that do not require People
        // eligibility never enter this intersection.
        if (keys.isEmpty()) return null;
        if (grant instanceof ProductSurfaceContextDtos.CapabilityGrant capability) {
            return new ProductSurfaceContextDtos.CapabilityGrant(
                    capability.capabilityContractKey(), capability.resolvedCapabilityCode(),
                    capability.authorityMode(), capability.predicatePolicyKeys(),
                    capability.responsibilityRequirement(), capability.responsibility(), keys,
                    capability.requiresProductEntitlement(), capability.readOnly(),
                    capability.activationState(), capability.validUntil());
        }
        ProductSurfaceContextDtos.PolicyGrant policy =
                (ProductSurfaceContextDtos.PolicyGrant) grant;
        return new ProductSurfaceContextDtos.PolicyGrant(
                policy.accessPolicyKey(), policy.policyDecisionRef(), policy.authorityMode(),
                keys, policy.requiresProductEntitlement(), policy.readOnly(), policy.validUntil());
    }

    static List<ProductSurfaceContextDtos.EffectiveScope> normalizeReadOnly(
            List<ProductSurfaceContextDtos.EffectiveScope> scopes,
            List<ProductSurfaceContextDtos.EffectiveGrant> grants) {
        return scopes.stream().map(scope -> new ProductSurfaceContextDtos.EffectiveScope(
                scope.key(), scope.kind(), scope.displayName(), scope.isDefault(),
                scope.readOnly() || !hasActiveMutationGrant(grants, scope.key()),
                scope.validUntil())).toList();
    }

    private static boolean hasActiveMutationGrant(
            List<ProductSurfaceContextDtos.EffectiveGrant> grants,
            String scopeKey) {
        return grants.stream()
                .filter(grant -> grant.scopeKeys().contains(scopeKey))
                .filter(grant -> !grant.readOnly())
                .anyMatch(grant -> !(grant instanceof ProductSurfaceContextDtos.CapabilityGrant cap)
                        || "ACTIVE".equals(cap.activationState()));
    }

    static boolean closed(
            List<ProductSurfaceContextDtos.EffectiveGrant> grants,
            List<ProductSurfaceContextDtos.EffectiveScope> scopes) {
        Set<String> keys = scopes.stream()
                .map(ProductSurfaceContextDtos.EffectiveScope::key)
                .collect(Collectors.toUnmodifiableSet());
        return grants.stream().allMatch(grant -> !grant.scopeKeys().isEmpty()
                && keys.containsAll(grant.scopeKeys()));
    }

    private static OffsetDateTime earliest(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ProductSurfaceAuthorityUnavailableException unavailable() {
        return new ProductSurfaceAuthorityUnavailableException();
    }

    record Intersection(
            List<ProductSurfaceContextDtos.EffectiveGrant> grants,
            List<ProductSurfaceContextDtos.EffectiveScope> scopes) {
    }

    private record DerivedAlias(
            ProductSurfaceContextDtos.EffectiveScope scope,
            boolean ownerReadOnly) {
    }
}
