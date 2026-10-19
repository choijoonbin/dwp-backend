package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.dwp.platform.contracts.hris.referenceconfiguration.Per004TargetActionsV2;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Seal the exact IAQ-056 state matrix before using the shared descriptor builder. */
final class Per004ActionSelector {
    private Per004ActionSelector() { }

    static List<HrisReferenceConfigurationV1.TargetActionV2> select(
            HrisReferenceConfigurationV1.OwnerProjection projection,
            OffsetDateTime asOf, OffsetDateTime now, Set<String> viewFields,
            Set<String> allowedRoutes, HrisReferenceConfigurationV1.FeatureDecision feature,
            UUID subject, long tenantId, long authorizationRevision,
            boolean makerCheckerAndStepUpSatisfied) {
        if (projection == null || now == null) return List.of();
        String state = projection.lifecycleState();
        boolean eligible = switch (state == null ? "" : state) {
            case "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE" -> projection.createEligible();
            case "DRAFT" -> projection.ownerFinalized();
            case "VALIDATED", "SIMULATED", "PENDING_APPROVAL" -> true;
            case "APPROVED" -> due(projection.effectiveFrom(), now);
            case "SCHEDULED" -> due(projection.effectiveFrom(), now)
                    && due(projection.scheduledAt(), now);
            default -> false;
        };
        if (!eligible) return List.of();
        return Per004TargetActionsV2.settings(projection, asOf, now, viewFields,
                allowedRoutes, feature, subject, tenantId, authorizationRevision,
                makerCheckerAndStepUpSatisfied);
    }

    private static boolean due(OffsetDateTime when, OffsetDateTime now) {
        return when != null && !now.isBefore(when);
    }
}
