package com.dwp.services.platform.hrisconfiguration.explorer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

/** Typed, authorization-filtered metadata port for XCON-012 through XCON-015 owners. */
public interface HrisExplorerOwnerEvidenceProvider {

    SourceKind sourceKind();

    Module module();

    ProviderResult findInProgress(ProviderQuery query);

    record ProviderQuery(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String contextScopeKey,
            String authorityPopulationScopeDigest,
            String purposeCode,
            long authorizationRevision,
            long fieldPolicyRevision,
            OffsetDateTime asOf,
            OffsetDateTime evidenceEvaluatedAt,
            Set<String> allowedRouteKeys,
            Set<ExplorerTarget> allowedTargets) {

        public ProviderQuery {
            allowedRouteKeys = allowedRouteKeys == null
                    ? Set.of() : Set.copyOf(allowedRouteKeys);
            allowedTargets = allowedTargets == null ? Set.of() : Set.copyOf(allowedTargets);
        }
    }

    record ProviderResult(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String contextScopeKey,
            long authorizationRevision,
            long fieldPolicyRevision,
            long sourceRevision,
            OffsetDateTime generatedAt,
            OffsetDateTime freshUntil,
            List<ExplorerActivity> activities) {

        public ProviderResult {
            activities = activities == null ? null : List.copyOf(activities);
        }
    }
}
