package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;

import java.util.List;
import java.util.Map;

record Registry(
        List<ProductAuthorizationContractDtos.CapabilityContract> capabilities,
        List<ProductAuthorizationContractDtos.AccessPolicy> policies,
        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilitiesByKey,
        Map<String, ProductAuthorizationContractDtos.AccessPolicy> policiesByKey,
        Map<String, ProductAuthorizationContractDtos.EntitlementExpression> expressionsByKey,
        Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicatesByKey,
        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routesByKey) {

    Registry(ProductAuthorizationContractDtos.BundleContract contract) {
        this(
                contract.capabilities(),
                contract.accessPolicies(),
                index(contract.capabilities(),
                        ProductAuthorizationContractDtos.CapabilityContract::contractKey),
                index(contract.accessPolicies(),
                        ProductAuthorizationContractDtos.AccessPolicy::accessPolicyKey),
                index(contract.entitlementExpressions(),
                        ProductAuthorizationContractDtos.EntitlementExpression::expressionKey),
                index(contract.predicatePolicies(),
                        ProductAuthorizationContractDtos.PredicatePolicy::predicatePolicyKey),
                index(contract.routes(),
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey));
    }

    boolean hasSurface(String productKey, String surfaceKey) {
        return capabilities.stream().anyMatch(value -> productKey.equals(value.productKey())
                && surfaceKey.equals(value.surfaceKey()))
                || policies.stream().anyMatch(value -> productKey.equals(value.productKey())
                        && surfaceKey.equals(value.surfaceKey()))
                || routesByKey.values().stream().anyMatch(value ->
                        "PRODUCT".equals(value.subject().type())
                                && productKey.equals(value.subject().productKey())
                                && surfaceKey.equals(value.subject().surfaceKey()));
    }

    /**
     * Product participation is derived from the exact bundle selected by the active pointer.
     * Keeping this distinct from {@link #hasSurface(String, String)} lets callers distinguish a
     * pre-contract compatibility product from drift inside a participating product contract.
     */
    boolean hasProduct(String productKey) {
        return capabilities.stream().anyMatch(value -> productKey.equals(value.productKey()))
                || policies.stream().anyMatch(value -> productKey.equals(value.productKey()))
                || routesByKey.values().stream().anyMatch(value ->
                        "PRODUCT".equals(value.subject().type())
                                && productKey.equals(value.subject().productKey()));
    }

    private static <T> Map<String, T> index(
            List<T> values,
            java.util.function.Function<T, String> key) {
        return values.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                key, java.util.function.Function.identity()));
    }
}
