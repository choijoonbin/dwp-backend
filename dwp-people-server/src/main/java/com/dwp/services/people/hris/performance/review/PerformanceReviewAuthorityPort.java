package com.dwp.services.people.hris.performance.review;

import java.util.UUID;

/** Owner-side boundary for the generated v42 assigned-review PEP decision. */
public interface PerformanceReviewAuthorityPort {

    AuthorityEvidence authorize(AuthorityRequest request);

    record AuthorityRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            UUID reviewId,
            String operation,
            String purposeCode,
            String populationKind,
            String expectedDecisionRevision) {
    }

    record AuthorityEvidence(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            UUID reviewId,
            String operation,
            String purposeCode,
            String populationKind,
            String profileKey,
            String routeContractKey,
            String projectionPolicyKey,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision) {
    }
}
