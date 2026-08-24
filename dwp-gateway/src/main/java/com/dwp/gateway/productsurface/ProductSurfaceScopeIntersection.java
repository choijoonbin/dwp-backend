package com.dwp.gateway.productsurface;

import java.time.OffsetDateTime;
import java.util.HashSet;
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
        Set<String> derivedKeys = new HashSet<>();
        List<ProductSurfaceContextDtos.EffectiveScope> scopes = eligibleScopes.stream()
                .map(eligible -> derivedScope(sourceScopes, derivedKeys, eligible))
                .toList();
        if (scopes.isEmpty()) throw unavailable();
        List<ProductSurfaceContextDtos.EffectiveGrant> rebound = grants.stream()
                .map(grant -> rebind(grant, eligibleScopes))
                .toList();
        return new Intersection(rebound, scopes);
    }

    private static ProductSurfaceContextDtos.EffectiveScope derivedScope(
            Map<String, ProductSurfaceContextDtos.EffectiveScope> sources,
            Set<String> derivedKeys,
            ProductSurfaceContextDtos.EligibleScope eligible) {
        ProductSurfaceContextDtos.EffectiveScope source =
                sources.get(eligible.sourceScopeKey());
        if (source == null || blank(eligible.key()) || blank(eligible.kind())
                || !derivedKeys.add(eligible.key())) {
            throw unavailable();
        }
        return new ProductSurfaceContextDtos.EffectiveScope(
                eligible.key(), eligible.kind(), eligible.displayName(), eligible.isDefault(),
                source.readOnly() || eligible.readOnly(),
                earliest(source.validUntil(), eligible.validUntil()));
    }

    private static ProductSurfaceContextDtos.EffectiveGrant rebind(
            ProductSurfaceContextDtos.EffectiveGrant grant,
            List<ProductSurfaceContextDtos.EligibleScope> eligibleScopes) {
        List<String> keys = eligibleScopes.stream()
                .filter(scope -> grant.scopeKeys().contains(scope.sourceScopeKey()))
                .map(ProductSurfaceContextDtos.EligibleScope::key)
                .distinct()
                .toList();
        if (keys.isEmpty()) throw unavailable();
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

    private static ProductSurfaceContextAggregationService.AuthorityUnavailableException
            unavailable() {
        return new ProductSurfaceContextAggregationService.AuthorityUnavailableException();
    }

    record Intersection(
            List<ProductSurfaceContextDtos.EffectiveGrant> grants,
            List<ProductSurfaceContextDtos.EffectiveScope> scopes) {
    }
}
