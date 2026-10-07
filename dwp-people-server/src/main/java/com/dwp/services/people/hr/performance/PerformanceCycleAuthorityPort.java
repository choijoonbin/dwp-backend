package com.dwp.services.people.hr.performance;

import java.util.UUID;

/**
 * Owner boundary for canonical APP.HRIS entitlement and DATA.HR_TALENT PEP decisions.
 * Implementations must return only evidence validated by the policy authority.
 */
public interface PerformanceCycleAuthorityPort {

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
            UUID aggregateId) {
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
