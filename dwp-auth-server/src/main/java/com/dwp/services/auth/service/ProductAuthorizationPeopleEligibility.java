package com.dwp.services.auth.service;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

import java.util.Set;

/**
 * Identifies authority decisions that must be intersected with People-owned eligibility.
 *
 * <p>Profile predicates, target bindings, and execution-service ownership are deliberately not
 * treated as ownership markers: payroll, time, and platform routes can still carry an HCM scope
 * owned by People.</p>
 */
final class ProductAuthorizationPeopleEligibility {

    private static final String PLATFORM_RECEIPT_ROUTE =
            "route.hcm.utility.explorer-command-receipt.data";
    private static final String PLATFORM_RECEIPT_CAPABILITY =
            "hcm.command-receipt.view";
    private static final String PLATFORM_RECEIPT_PROFILE =
            "platform-originating-command-receipt-reader";
    private static final String PLATFORM_RECEIPT_SCOPE_RESOLVER =
            "ORIGINATING_COMMAND_SCOPE";
    private static final Set<String> HCM_SCOPE_KINDS =
            Set.of("SELF", "TARGET_POPULATION", "RESOURCE_SET");

    private ProductAuthorizationPeopleEligibility() {
    }

    static boolean requiredForRoute(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Evaluation result) {
        if (supportMode(request)) return false;
        if (exactOriginatingCommandReceiptGrant(request, route, result)) return false;
        return hasPeoplePepBinding(route)
                || ("hcm".equals(request.productKey())
                        && result.scopes().stream()
                                .map(ProductSurfaceAuthorityDtos.EffectiveScope::kind)
                                .anyMatch(HCM_SCOPE_KINDS::contains));
    }

    private static boolean exactOriginatingCommandReceiptGrant(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Evaluation result) {
        if (!PLATFORM_RECEIPT_ROUTE.equals(request.routeContractKey())
                || !PLATFORM_RECEIPT_ROUTE.equals(route.routeContractKey())
                || !"hcm".equals(request.productKey())
                || !"hcm.utility".equals(request.surfaceKey())
                || !result.allowed()
                || result.grants().size() != 1
                || result.scopes().size() != 1
                || !"TARGET_POPULATION".equals(result.scopes().getFirst().kind())) {
            return false;
        }
        String expectedScopeKey = ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), request.productKey(),
                request.surfaceKey(), PLATFORM_RECEIPT_SCOPE_RESOLVER,
                "TARGET_POPULATION");
        if (!expectedScopeKey.equals(result.scopes().getFirst().key())) return false;
        ProductSurfaceAuthorityDtos.EffectiveGrant grant = result.grants().getFirst();
        if (!(grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant capability)
                || !PLATFORM_RECEIPT_CAPABILITY.equals(
                        capability.capabilityContractKey())
                || capability.authorityMode()
                != ProductSurfaceAuthorityDtos.CapabilityAuthorityMode
                .PERMISSION_AND_RELATIONSHIP
                || capability.requiresProductEntitlement()
                || !capability.scopeKeys().equals(java.util.List.of(expectedScopeKey))) {
            return false;
        }
        return route.accessProfiles().stream().anyMatch(profile ->
                PLATFORM_RECEIPT_PROFILE.equals(profile.profileKey())
                        && profile.requiredAccess() != null
                        && "CAPABILITY".equals(profile.requiredAccess().type())
                        && PLATFORM_RECEIPT_CAPABILITY.equals(
                                profile.requiredAccess().capabilityContractKey()));
    }

    static boolean requiredForSurface(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry) {
        if (supportMode(request)) return false;
        return registry.routesByKey().values().stream()
                .filter(route -> "PRODUCT".equals(route.subject().type()))
                .filter(route -> request.productKey().equals(route.subject().productKey()))
                .filter(route -> request.surfaceKey().equals(route.subject().surfaceKey()))
                .anyMatch(route -> route.accessProfiles().stream()
                        .anyMatch(profile -> profile.activeAccessModes().contains(
                                request.activeAccessMode().name()))
                        && hasPeoplePepBinding(route));
    }

    private static boolean supportMode(ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        return request.activeAccessMode()
                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT;
    }

    private static boolean hasPeoplePepBinding(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        return route.servicePepBindings() != null
                && route.servicePepBindings().stream()
                        .anyMatch(value -> "people".equals(value.serviceKey()));
    }
}
