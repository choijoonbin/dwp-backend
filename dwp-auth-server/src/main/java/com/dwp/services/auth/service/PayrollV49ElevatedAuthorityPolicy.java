package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact NORMAL-challenge admission for sealed elevated payroll routes and v54 result reads. */
final class PayrollV49ElevatedAuthorityPolicy {

    static final String SUPPORT_CAPABILITY = "hcm.operations.payroll-support.view";
    static final String RUN_CAPABILITY = "hcm.operations.payroll-run.execute";
    static final String POPULATION_PREDICATE =
            "predicate.hcm-payroll-population-authority.v1";
    static final String RUN_PREDICATE = "predicate.hcm-payroll-run-authority.v1";
    static final String PREPARE_PREDICATE =
            "predicate.hcm-payroll-run-prepare-authority.v1";
    static final String WIRE_AUTHORITY =
            "wire-authority.hcm.operations.payroll-population.v1";
    static final String STATUTORY_RESULT_WIRE_AUTHORITY =
            "wire-authority.hcm.operations.pay-tax-social-insurance.v1";

    static final Set<String> LEGACY_READ_ROUTES = Set.of(
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data");
    static final Set<String> STATUTORY_READ_ROUTES = Set.of(
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data");
    static final Set<String> READ_ROUTES = Set.of(
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data");
    static final Set<String> COMMAND_ROUTES = Set.of(
            "route.hcm.operations.payroll-run.action",
            "route.hcm.operations.payroll-run-calculate.action");
    static final Set<String> ROUTES = Set.of(
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data",
            "route.hcm.operations.payroll-run.action",
            "route.hcm.operations.payroll-run-calculate.action");

    private PayrollV49ElevatedAuthorityPolicy() {
    }

    static boolean isNormalChallengeCandidate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities) {
        return request.activeAccessMode() == ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                && isExactRoute(route, capabilities);
    }

    static boolean isExactCommandRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities) {
        return route != null && COMMAND_ROUTES.contains(route.routeContractKey())
                && isExactRoute(route, capabilities);
    }

    static boolean isExactRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities) {
        if (route == null || capabilities == null || !ROUTES.contains(route.routeContractKey())
                || route.subject() == null
                || !"PRODUCT".equals(route.subject().type())
                || !"hcm".equals(route.subject().productKey())
                || !"hcm.operations".equals(route.subject().surfaceKey())
                || !"hcm.operations".equals(route.navigationContextId())
                || !"Payroll + HCM + Security".equals(route.owner())
                || route.policyVersion() != 1
                || !"ACTIVE".equals(route.lifecycleState())
                || route.accessProfiles() == null || route.accessProfiles().size() != 1
                || route.gatewayApiBindings() == null || route.gatewayApiBindings().size() != 1
                || route.servicePepBindings() == null || route.servicePepBindings().size() != 1
                || !empty(route.consumedApiRouteContractKeys())) {
            return false;
        }
        return READ_ROUTES.contains(route.routeContractKey())
                ? exactRead(route, capabilities.get(SUPPORT_CAPABILITY))
                : exactCommand(route, capabilities.get(RUN_CAPABILITY));
    }

    private static boolean exactRead(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        boolean statutory = route.routeContractKey()
                .startsWith("route.hcm.operations.pay-tax-social-insurance.");
        boolean page = route.routeContractKey().endsWith(".page");
        String bindingKey = route.routeContractKey() + ".binding.01";
        var profile = route.accessProfiles().getFirst();
        var access = profile.requiredAccess();
        var projections = profile.responseProjectionBindings();
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        return (page ? "PAGE" : "DATA").equals(route.routeKind())
                && (page ? route.sideEffectFree() == null
                        : Boolean.TRUE.equals(route.sideEffectFree()))
                && (page
                        ? (statutory
                                ? "hcm.operations.pay-tax-social-insurance"
                                        .equals(route.uiRouteId())
                                        && "/hr/operations/payroll/tax-social-insurance"
                                        .equals(route.uiRoutePattern())
                                : "hcm.operations.payroll-population"
                                        .equals(route.uiRouteId())
                                        && "/hr/operations/payroll/population"
                                        .equals(route.uiRoutePattern()))
                        : route.uiRouteId() == null && route.uiRoutePattern() == null)
                && (statutory ? STATUTORY_RESULT_WIRE_AUTHORITY : WIRE_AUTHORITY)
                .equals(route.authorizationEquivalenceKey())
                && empty(route.stepUpCommandBindings())
                && "payroll-support-viewer".equals(profile.profileKey())
                && profile.precedence() == 300
                && (statutory ? List.of("NORMAL", "ELEVATED") : List.of("ELEVATED"))
                .equals(profile.activeAccessModes())
                && profile.readOnly()
                && List.of("TARGET_POPULATION").equals(profile.targetBindingKinds())
                && List.of(POPULATION_PREDICATE).equals(profile.predicatePolicyKeys())
                && access != null && "CAPABILITY".equals(access.type())
                && SUPPORT_CAPABILITY.equals(access.capabilityContractKey())
                && projections != null && projections.size() == 1
                && bindingKey.equals(projections.getFirst().apiBindingKey())
                && (statutory ? "pay003.iaq-048.fields.v1" : route.routeContractKey()
                + ".payroll-support-viewer.projection.v1")
                .equals(projections.getFirst().projectionPolicyKey())
                && (statutory ? "Pay003TaxSocialInsuranceProjectionV1"
                        : "route.hcm.operations.payroll-support.data.response.v1")
                .equals(projections.getFirst().responseSchemaKey())
                && exactWire(gateway, service, bindingKey, "GET",
                        statutory ? "/api/payroll/v1/workbench/pay-tax-social-insurance"
                                : "/api/payroll/v1/workbench/population",
                        statutory ? "/v1/workbench/pay-tax-social-insurance"
                                : "/v1/workbench/population")
                && exactSupportCapability(capability, statutory);
    }

    private static boolean exactCommand(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        boolean prepare = "route.hcm.operations.payroll-run.action"
                .equals(route.routeContractKey());
        String bindingKey = route.routeContractKey() + ".binding.01";
        var profile = route.accessProfiles().getFirst();
        var access = profile.requiredAccess();
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        if (!"ACTION".equals(route.routeKind()) || route.sideEffectFree() != null
                || route.uiRouteId() != null || route.uiRoutePattern() != null
                || route.authorizationEquivalenceKey() != null
                || !"payroll-run-operator".equals(profile.profileKey())
                || profile.precedence() != 300
                || !List.of("ELEVATED").equals(profile.activeAccessModes())
                || profile.readOnly()
                || !List.of("OBJECT", "TARGET_POPULATION")
                .equals(profile.targetBindingKinds())
                || !List.of(prepare ? PREPARE_PREDICATE : RUN_PREDICATE)
                .equals(profile.predicatePolicyKeys())
                || !empty(profile.responseProjectionBindings())
                || access == null || !"CAPABILITY".equals(access.type())
                || !RUN_CAPABILITY.equals(access.capabilityContractKey())
                || !exactWire(gateway, service, bindingKey, "POST",
                        prepare ? "/api/payroll/v1/runs"
                                : "/api/payroll/v1/runs/{runId}/attempts",
                        prepare ? "/v1/runs" : "/v1/runs/{runId}/attempts")
                || route.stepUpCommandBindings() == null
                || route.stepUpCommandBindings().size() != 1) {
            return false;
        }
        var stepUp = route.stepUpCommandBindings().getFirst();
        return bindingKey.equals(stepUp.bindingKey())
                && "payroll".equals(stepUp.ownerServiceKey())
                && "dwp-payroll-server".equals(stepUp.audience())
                && (prepare ? "PAYROLL_RUN_PREPARATION" : "PAYROLL_RUN")
                .equals(stepUp.targetType())
                && (prepare
                        ? stepUp.targetIdPathParameter() == null
                                && List.of("preparationId").equals(stepUp.targetIdBodyFields())
                        : "runId".equals(stepUp.targetIdPathParameter())
                                && empty(stepUp.targetIdBodyFields()))
                && "COMMAND_HEADER".equals(stepUp.expectedObjectVersionSource())
                && "X-DWP-Expected-Object-Version"
                .equals(stepUp.expectedObjectVersionName())
                && exactRunCapability(capability);
    }

    private static boolean exactWire(
            ProductAuthorizationContractDtos.GatewayBinding gateway,
            ProductAuthorizationContractDtos.ServicePepBinding service,
            String bindingKey,
            String method,
            String gatewayPath,
            String servicePath) {
        return bindingKey.equals(gateway.bindingKey())
                && method.equals(gateway.method())
                && gatewayPath.equals(gateway.path())
                && empty(gateway.pathParameterConstraints())
                && empty(gateway.queryParameterConstraints())
                && bindingKey.equals(service.bindingKey())
                && "payroll".equals(service.serviceKey())
                && method.equals(service.method())
                && servicePath.equals(service.path())
                && empty(service.pathParameterConstraints())
                && empty(service.queryParameterConstraints());
    }

    private static boolean exactSupportCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability,
            boolean statutoryRoute) {
        boolean legacy = exactCapability(capability, SUPPORT_CAPABILITY,
                "DATA.HRIS_PAYROLL_STATUS:VIEW", "DATA.HRIS_PAYROLL_STATUS", "VIEW",
                Set.of(
                        "route.hcm.operations.payroll-population.page",
                        "route.hcm.operations.payroll-run.data",
                        "route.hcm.operations.payroll-support.data"),
                "PAY_TARGET_POPULATION", "HIGH");
        boolean expanded = exactCapability(capability, SUPPORT_CAPABILITY,
                "DATA.HRIS_PAYROLL_STATUS:VIEW", "DATA.HRIS_PAYROLL_STATUS", "VIEW",
                Set.of(
                        "route.hcm.operations.payroll-population.page",
                        "route.hcm.operations.payroll-run.data",
                        "route.hcm.operations.payroll-support.data",
                        "route.hcm.operations.pay-tax-social-insurance.page",
                        "route.hcm.operations.pay-tax-social-insurance.data"),
                "PAY_TARGET_POPULATION", "HIGH");
        return statutoryRoute ? expanded : legacy || expanded;
    }

    private static boolean exactRunCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return exactCapability(capability, RUN_CAPABILITY,
                "ACTION.HRIS_PAYROLL_RUN:EXECUTE", "ACTION.HRIS_PAYROLL_RUN", "EXECUTE",
                Set.of(
                        "route.hcm.operations.payroll-run-calculate.action",
                        "route.hcm.operations.payroll-run.action",
                        "route.hcm.operations.payroll-run.data"),
                "PAYROLL_LEGAL_ENTITY_GROUP_SCOPE", "CRITICAL");
    }

    private static boolean exactCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability,
            String contractKey,
            String resolvedCode,
            String resourceKey,
            String action,
            Set<String> routes,
            String scopeResolver,
            String riskTier) {
        return capability != null
                && contractKey.equals(capability.contractKey())
                && resolvedCode.equals(capability.resolvedCapabilityCode())
                && capability.mappingVersion() == 1
                && "hcm".equals(capability.productKey())
                && "hcm.operations".equals(capability.surfaceKey())
                && capability.routeContractKeys() != null
                && routes.equals(Set.copyOf(capability.routeContractKeys()))
                && resourceKey.equals(capability.resourceKey())
                && action.equals(capability.action())
                && "PERMISSION_AND_RELATIONSHIP".equals(capability.authorityMode())
                && "NOT_REQUIRED".equals(capability.responsibilityRequirement())
                && capability.requiredResponsibilityCode() == null
                && scopeResolver.equals(capability.scopeResolver())
                && riskTier.equals(capability.riskTier())
                && "STEPUP-MGMT-HIGH-V1".equals(capability.activationPolicy())
                && capability.sodPolicyId() == null
                && !capability.requiresProductEntitlement()
                && "Payroll + HCM + Security".equals(capability.owner())
                && capability.sunsetAt() == null
                && capability.legacySource() == null
                && capability.policyVersion() == 1
                && "ACTIVE".equals(capability.lifecycleState());
    }

    private static boolean empty(java.util.Collection<?> value) {
        return value == null || value.isEmpty();
    }

    private static boolean empty(Map<?, ?> value) {
        return value == null || value.isEmpty();
    }
}
