package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

import java.time.OffsetDateTime;
import java.util.List;

record Evaluation(
        ProductSurfaceAuthorityDtos.Decision decision,
        String reasonCode,
        ProductSurfaceAuthorityDtos.AccessSource accessSource,
        List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants,
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes,
        String routeGrantRef,
        boolean effectiveReadOnly,
        boolean requiresProductEligibility,
        OffsetDateTime validUntil,
        String requiredAssurance,
        String requestPolicyRef,
        String appResourceKey) {

    static Evaluation denied(
            ProductSurfaceAuthorityDtos.Decision decision,
            String reasonCode) {
        return new Evaluation(decision, reasonCode, null, List.of(), List.of(),
                null, true, false, null, null, null, null);
    }

    static Evaluation challenge(
            ProductSurfaceAuthorityDtos.Decision decision,
            String reasonCode,
            String policyRef,
            String configuredAcr,
            ProductSurfaceAuthorityDtos.AccessSource accessSource,
            List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes,
            boolean readOnly,
            OffsetDateTime validUntil,
            String appResourceKey) {
        StepUpAssurancePolicy policy = StepUpAssurancePolicy.resolve(policyRef, configuredAcr);
        if (policy == null) return denied(ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                "STEP_UP_ASSURANCE_POLICY_UNAVAILABLE");
        return new Evaluation(decision, reasonCode, accessSource,
                List.copyOf(grants), List.copyOf(scopes), null, readOnly, false,
                validUntil, policy.requiredAcr(), policy.policyId(), appResourceKey);
    }

    static Evaluation allowed(
            ProductSurfaceAuthorityDtos.AccessSource accessSource,
            List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes,
            boolean readOnly,
            OffsetDateTime validUntil,
            boolean requiresProductEligibility,
            String appResourceKey) {
        return new Evaluation(ProductSurfaceAuthorityDtos.Decision.ALLOWED, "ALLOWED",
                accessSource, List.copyOf(grants), List.copyOf(scopes), null,
                readOnly, requiresProductEligibility, validUntil, null, null,
                appResourceKey);
    }

    boolean allowed() {
        return decision == ProductSurfaceAuthorityDtos.Decision.ALLOWED;
    }

    Evaluation withRoute(String grantRef, boolean eligibility) {
        return new Evaluation(decision, reasonCode, accessSource, grants, scopes,
                grantRef, effectiveReadOnly, eligibility, validUntil,
                requiredAssurance, requestPolicyRef, appResourceKey);
    }

    Evaluation withProductEligibility(boolean eligibility) {
        return new Evaluation(decision, reasonCode, accessSource, grants, scopes,
                routeGrantRef, effectiveReadOnly, eligibility, validUntil,
                requiredAssurance, requestPolicyRef, appResourceKey);
    }

    Evaluation forRoute() {
        if (decision == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED
                || decision == ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT
                || decision == ProductSurfaceAuthorityDtos.Decision.ACTIVATION_REQUIRED
                || decision == ProductSurfaceAuthorityDtos.Decision.EXPIRED
                || decision == ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED) {
            return this;
        }
        return denied(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                "ROUTE_CAPABILITY_REQUIRED");
    }

    private record StepUpAssurancePolicy(String policyId, String requiredAcr) {
        static StepUpAssurancePolicy resolve(String policyId, String configuredAcr) {
            if (!("STEPUP-MGMT-HIGH-V1".equals(policyId) || "STEPUP-MGMT-CRITICAL-V1".equals(policyId))
                    || configuredAcr == null || configuredAcr.isBlank()
                    || configuredAcr.length() > 200 || !configuredAcr.equals(configuredAcr.trim())) return null;
            return new StepUpAssurancePolicy(policyId, configuredAcr);
        }
    }
}
