package com.dwp.services.platform.hrisconfiguration.explorer;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

/**
 * Owner-query port. Implementations must return server-resolved current evidence; request
 * headers, IA lifecycle values and client role lists are not authority inputs.
 */
public interface HrisExplorerAuthoritySource {

    Optional<AuthoritySnapshot> resolve(AuthorityRequest request);

    record AuthorityRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            String activeAccessMode,
            String currentDecisionRevision,
            String trustedRouteContractKey,
            String contextKey,
            String contextScopeKey,
            String requestPurposeCode,
            OffsetDateTime revalidateAt) {
    }

    record AuthoritySnapshot(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String currentDecisionRevision,
            String contextKey,
            String contextScopeKey,
            String purposeCode,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            long entitlementRevision,
            long projectionRevision,
            OffsetDateTime freshUntil,
            Map<String, ExplorerAuthorization> allowedRouteDecisions) {

        public AuthoritySnapshot {
            allowedRouteDecisions = allowedRouteDecisions == null
                    ? Map.of() : Map.copyOf(allowedRouteDecisions);
        }
    }
}
