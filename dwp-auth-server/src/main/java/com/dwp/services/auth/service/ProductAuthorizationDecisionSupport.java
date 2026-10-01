package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideReader;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.digest;
import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.plane;

/** Keeps tenant override revisions and final authority evidence in one fail-safe boundary. */
final class ProductAuthorizationDecisionSupport {

    private final TenantCapabilityOverrideReader overrides;
    private final Clock clock;

    ProductAuthorizationDecisionSupport(
            TenantCapabilityOverrideReader overrides,
            Clock clock) {
        this.overrides = overrides;
        this.clock = clock;
    }

    static ProductAuthorizationDecisionSupport from(
            ObjectProvider<TenantCapabilityOverrideReader> overrides) {
        return new ProductAuthorizationDecisionSupport(
                overrides.getIfAvailable(TenantCapabilityOverrideReader::none),
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

    ProductSurfaceAuthorityDtos.AuthorityResult result(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String authRevision,
            String policyRevision,
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
                "evidence-" + digest(authRevision + policyRevision).substring(0, 24));
    }
}
