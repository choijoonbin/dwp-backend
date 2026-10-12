package com.dwp.services.people.hr.performance.populationfreeze;

import java.util.UUID;

/**
 * Owner-side authority boundary for PEP-PER-009. Implementations must return only
 * current evidence for the exact population-freeze route and selected population.
 */
public interface PopulationFreezeAuthorityPort {

    AuthorityEvidence authorize(AuthorityRequest request);

    record AuthorityRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            String applicationEntitlement,
            String resource,
            String action,
            String operation,
            String purposeCode,
            UUID cycleId) {
    }

    record AuthorityEvidence(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            String applicationEntitlement,
            String resource,
            String action,
            String operation,
            String purposeCode,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision) {
    }
}
