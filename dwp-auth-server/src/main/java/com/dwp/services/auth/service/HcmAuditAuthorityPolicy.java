package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed authority policy for the two HCM audit-evidence read routes. */
final class HcmAuditAuthorityPolicy {

    static final String CAPABILITY = "hcm.audit.evidence.view";
    static final String PREDICATE = "predicate.hcm-audit-evidence-authority.v1";
    static final String RESPONSIBILITY = "HRIS_DUTY_AUDITOR";
    static final String RESOURCE_SET = "RS_HCM_CONFIG";
    static final String PRODUCT_RESOURCE = "APP.HCM";

    static final Set<String> ROUTES = Set.of(
            "route.hcm.audit.evidence.page",
            "route.hcm.audit.evidence-detail.data");

    static final Set<String> CONFLICTING_PACKAGES = Set.of(
            "HRIS_LINE_MANAGER",
            "HRIS_HR_BUSINESS_PARTNER",
            "HRIS_HR_LIFECYCLE_APPROVER",
            "HRIS_HR_SENSITIVE_DATA_READER",
            "HRIS_HR_COMPENSATION_READER",
            "HRIS_WORKFORCE_EXPORT_OPERATOR",
            "HRIS_TIMEKEEPER",
            "HRIS_TIME_APPROVER",
            "HRIS_TIME_CONTROLLER",
            "HRIS_LEAVE_ADMINISTRATOR",
            "HRIS_LEAVE_APPROVER",
            "HRIS_LEAVE_BALANCE_ADJUSTER",
            "HRIS_LEAVE_ADJUSTMENT_APPROVER",
            "HRIS_PAYROLL_SPECIALIST",
            "HRIS_PAYROLL_RUN_OPERATOR",
            "HRIS_PAYROLL_VALIDATOR",
            "HRIS_PAYROLL_CONTROLLER",
            "HRIS_PAYROLL_RELEASE_OFFICER",
            "HRIS_PAYROLL_ACCOUNTING",
            "HRIS_BANK_TAX_MAINTAINER",
            "HRIS_STATUTORY_OPERATOR",
            "HRIS_PERFORMANCE_OPERATOR",
            "HRIS_PERFORMANCE_CALIBRATOR",
            "HRIS_PERFORMANCE_RESULT_PUBLISHER",
            "HRIS_CONFIG_AUTHOR",
            "HRIS_CONFIG_PUBLISHER",
            "HRIS_INTEGRATION_AUTHOR",
            "HRIS_INTEGRATION_EXECUTOR");

    private HcmAuditAuthorityPolicy() {
    }

    static boolean isNormalChallengeCandidate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return request.activeAccessMode() == ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                && isExactReadRoute(route, capability);
    }

    static boolean isExactReadRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        if (route == null || capability == null || !ROUTES.contains(route.routeContractKey())
                || !"PRODUCT".equals(route.subject().type())
                || !"hcm".equals(route.subject().productKey())
                || !"hcm.management".equals(route.subject().surfaceKey())
                || !Boolean.TRUE.equals(route.sideEffectFree())
                || route.accessProfiles() == null || route.accessProfiles().size() != 1
                || route.gatewayApiBindings() == null || route.gatewayApiBindings().size() != 1
                || route.servicePepBindings() == null || route.servicePepBindings().size() != 1
                || route.stepUpCommandBindings() != null && !route.stepUpCommandBindings().isEmpty()
                || !exactCapability(capability)) {
            return false;
        }
        boolean page = "route.hcm.audit.evidence.page".equals(route.routeContractKey());
        String suffix = page ? "" : "/{eventId}";
        String expectedSchema = page ? "HcmAuditEvidencePageV1" : "HcmAuditEvidenceV1";
        String expectedKind = page ? "PAGE" : "DATA";
        var profile = route.accessProfiles().getFirst();
        var access = profile.requiredAccess();
        var projection = profile.responseProjectionBindings();
        var gateway = route.gatewayApiBindings().getFirst();
        var service = route.servicePepBindings().getFirst();
        String bindingKey = route.routeContractKey() + ".binding.01";
        return expectedKind.equals(route.routeKind())
                && "hcm-audit-evidence-reader".equals(profile.profileKey())
                && profile.precedence() == 300
                && List.of("ELEVATED").equals(profile.activeAccessModes())
                && profile.readOnly()
                && List.of("CONFIG_SCOPE").equals(profile.targetBindingKinds())
                && List.of(PREDICATE).equals(profile.predicatePolicyKeys())
                && access != null && "CAPABILITY".equals(access.type())
                && CAPABILITY.equals(access.capabilityContractKey())
                && projection != null && projection.size() == 1
                && bindingKey.equals(projection.getFirst().apiBindingKey())
                && (route.routeContractKey() + ".hcm-evidence-minimized.projection.v1")
                .equals(projection.getFirst().projectionPolicyKey())
                && expectedSchema.equals(projection.getFirst().responseSchemaKey())
                && bindingKey.equals(gateway.bindingKey())
                && "GET".equals(gateway.method())
                && ("/api/platform/v1/admin/audit-control/events" + suffix)
                .equals(gateway.path())
                && exactProductQuery(gateway.queryParameterConstraints())
                && bindingKey.equals(service.bindingKey())
                && "platform".equals(service.serviceKey())
                && "GET".equals(service.method())
                && ("/v1/admin/audit-control/events" + suffix).equals(service.path())
                && exactProductQuery(service.queryParameterConstraints());
    }

    static boolean exactCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return CAPABILITY.equals(capability.contractKey())
                && "ADMIN.AUDIT_VIEW:VIEW".equals(capability.resolvedCapabilityCode())
                && "hcm".equals(capability.productKey())
                && "hcm.management".equals(capability.surfaceKey())
                && ROUTES.equals(Set.copyOf(capability.routeContractKeys()))
                && "ADMIN.AUDIT_VIEW".equals(capability.resourceKey())
                && "VIEW".equals(capability.action())
                && "PERMISSION_AND_RELATIONSHIP".equals(capability.authorityMode())
                && "REQUIRED".equals(capability.responsibilityRequirement())
                && RESPONSIBILITY.equals(capability.requiredResponsibilityCode())
                && "APP_RESOURCE_SET:RS_HCM_CONFIG".equals(capability.scopeResolver())
                && "HIGH".equals(capability.riskTier())
                && "STEPUP-MGMT-HIGH-V1".equals(capability.activationPolicy())
                && "SOD_HRIS_AUDIT_INDEPENDENCE".equals(capability.sodPolicyId())
                && capability.requiresProductEntitlement();
    }

    private static boolean exactProductQuery(
            Map<String, ProductAuthorizationContractDtos.QueryParameterConstraint> values) {
        if (values == null || values.size() != 1) return false;
        var product = values.get("product");
        return product != null && "FIXED".equals(product.kind())
                && "HCM".equals(product.value())
                && (product.values() == null || product.values().isEmpty());
    }

    static AuthorityEvidence evaluate(
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity) {
        if (!identity.hasPermission("APP.HCM:VIEW")) {
            return AuthorityEvidence.denied(Outcome.APP_ENTITLEMENT_REQUIRED);
        }
        if (!identity.hasPermission("ADMIN.AUDIT_VIEW:VIEW")
                || !identity.hasRole("HRIS_AUDITOR")) {
            return AuthorityEvidence.denied(Outcome.CAPABILITY_REQUIRED);
        }
        AppGovernanceDtos.ResourceRole responsibility = identity.responsibilities().stream()
                .filter(value -> RESPONSIBILITY.equals(value.responsibilityCode()))
                .filter(value -> PRODUCT_RESOURCE.equals(value.resourceKey()))
                .filter(value -> RESOURCE_SET.equals(value.resourceSetKey()))
                .findFirst()
                .orElse(null);
        if (responsibility == null) {
            return AuthorityEvidence.denied(Outcome.RESPONSIBILITY_REQUIRED);
        }
        if (identity.roles().stream().anyMatch(CONFLICTING_PACKAGES::contains)) {
            return AuthorityEvidence.denied(Outcome.SOD_CONFLICT);
        }
        return new AuthorityEvidence(Outcome.ALLOWED, responsibility);
    }

    enum Outcome {
        ALLOWED,
        APP_ENTITLEMENT_REQUIRED,
        CAPABILITY_REQUIRED,
        RESPONSIBILITY_REQUIRED,
        SOD_CONFLICT
    }

    record AuthorityEvidence(
            Outcome outcome,
            AppGovernanceDtos.ResourceRole responsibility) {
        static AuthorityEvidence denied(Outcome outcome) {
            return new AuthorityEvidence(outcome, null);
        }
    }
}
