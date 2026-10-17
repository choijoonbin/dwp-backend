package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideReader;
import com.dwp.services.auth.tenantfeatureactivation.TenantFeatureActivationReader;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.digest;
import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.plane;

/** Keeps tenant override revisions and final authority evidence in one fail-safe boundary. */
final class ProductAuthorizationDecisionSupport {

    private final TenantCapabilityOverrideReader overrides;
    private final TenantFeatureActivationReader featureActivations;
    private final Clock clock;

    ProductAuthorizationDecisionSupport(
            TenantCapabilityOverrideReader overrides,
            Clock clock) {
        this(overrides, TenantFeatureActivationReader.ungoverned(), clock);
    }

    ProductAuthorizationDecisionSupport(
            TenantCapabilityOverrideReader overrides,
            TenantFeatureActivationReader featureActivations,
            Clock clock) {
        this.overrides = overrides;
        this.featureActivations = featureActivations;
        this.clock = clock;
    }

    static ProductAuthorizationDecisionSupport from(
            ObjectProvider<TenantCapabilityOverrideReader> overrides,
            ObjectProvider<TenantFeatureActivationReader> featureActivations) {
        return new ProductAuthorizationDecisionSupport(
                overrides.getIfAvailable(TenantCapabilityOverrideReader::none),
                featureActivations.getIfAvailable(TenantFeatureActivationReader::unavailable),
                Clock.systemUTC());
    }

    static ProductAuthorizationDecisionSupport withoutOverrides(Clock clock) {
        return new ProductAuthorizationDecisionSupport(
                TenantCapabilityOverrideReader.none(), clock);
    }

    String contextRevision(Long tenantId, String policyRevision) {
        return policyRevision + "-tenant-"
                + overrides.effectiveRevision(tenantId, clock.instant());
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    boolean capabilityDisabled(Long tenantId, String contractKey) {
        return overrides.isDisabled(tenantId, contractKey, clock.instant());
    }

    TenantFeatureActivationReader.GateResult featureGate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            long bundleVersion,
            boolean existingAuthorityEligible) {
        if (!existingAuthorityEligible) {
            return TenantFeatureActivationReader.GateResult.authorizationNotEligible(
                    request.routeContractKey());
        }
        return featureActivations.evaluate(new TenantFeatureActivationReader.GateRequest(
                request.tenantId(), request.productKey(), request.surfaceKey(),
                request.routeContractKey(), bundleVersion, clock.instant()));
    }

    String tenantControlRevision(
            Long tenantId,
            TenantFeatureActivationReader.GateResult featureGate) {
        if (featureGate == null || featureGate.revision() == null
                || featureGate.revision().isBlank()) {
            return null;
        }
        String overrideRevision = overrides.effectiveRevision(tenantId, clock.instant());
        if (overrideRevision == null || overrideRevision.isBlank()) return null;
        return "tenant-control-v1-" + digest(
                "suppression=" + overrideRevision + "|feature=" + featureGate.revision());
    }

    ProductSurfaceAuthorityDtos.AuthorityResult result(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String authRevision,
            String policyRevision,
            String tenantControlRevision,
            String contextKey,
            OffsetDateTime revalidateAt,
            Evaluation evaluation) {
        boolean materialized = evaluation.allowed()
                || evaluation.decision()
                == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED;
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                evaluation.decision(),
                evaluation.reasonCode(),
                authRevision,
                policyRevision,
                tenantControlRevision,
                materialized ? contextKey : null,
                request.productKey(),
                request.surfaceKey(),
                materialized ? plane(request.surfaceKey()) : null,
                request.activeAccessMode(),
                evaluation.accessSource(),
                evaluation.appResourceKey(),
                evaluation.grants(),
                evaluation.scopes(),
                evaluation.routeGrantRef(),
                evaluation.effectiveReadOnly(),
                evaluation.requiresProductEligibility(),
                evaluation.validUntil(),
                null,
                evaluation.requiredAssurance(),
                evaluation.requestPolicyRef(),
                materialized ? revalidateAt : null,
                "evidence-" + digest(
                        authRevision + policyRevision + tenantControlRevision).substring(0, 24));
    }
}
