package com.dwp.services.auth.service;

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

    private static final Set<String> HCM_SCOPE_KINDS =
            Set.of("SELF", "TARGET_POPULATION", "RESOURCE_SET");

    private ProductAuthorizationPeopleEligibility() {
    }

    static boolean requiredForRoute(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Evaluation result) {
        if (supportMode(request)) return false;
        return hasPeoplePepBinding(route)
                || ("hcm".equals(request.productKey())
                        && result.scopes().stream()
                                .map(ProductSurfaceAuthorityDtos.EffectiveScope::kind)
                                .anyMatch(HCM_SCOPE_KINDS::contains));
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
