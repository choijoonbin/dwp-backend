package com.dwp.services.people.hr.performance.compensationplanning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owner PEP boundary. The module has no role-name or tenant fallback. */
public interface CompensationPlanningAuthorityPort {

    AuthorityEvidence authorize(AuthorityRequest request);

    enum FieldDecision {
        VIEW, MASK, OMIT
    }

    record AuthorityRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            String applicationEntitlement,
            String resource,
            String action,
            String operationId,
            String capability,
            String purposeCode,
            UUID aggregatePublicId) {
    }

    record AuthorityEvidence(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            String applicationEntitlement,
            String resource,
            String action,
            String operationId,
            String capability,
            String purposeCode,
            String populationScopeDigest,
            TargetPopulation targetPopulation,
            Map<String, FieldDecision> fieldDecisions,
            long fieldPolicyRevision,
            long authorizationRevision,
            boolean stepUpSatisfied) {
        public AuthorityEvidence {
            fieldDecisions = fieldDecisions == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(fieldDecisions));
        }
    }

    record TargetPopulation(
            boolean tenantWide,
            Set<UUID> organizationPublicIds,
            Set<UUID> workerPublicIds,
            Set<UUID> assignmentPublicIds) {
        public TargetPopulation {
            organizationPublicIds = organizationPublicIds == null
                    ? Set.of() : Set.copyOf(organizationPublicIds);
            workerPublicIds = workerPublicIds == null
                    ? Set.of() : Set.copyOf(workerPublicIds);
            assignmentPublicIds = assignmentPublicIds == null
                    ? Set.of() : Set.copyOf(assignmentPublicIds);
        }

        public boolean permitsOrganization(UUID value) {
            return tenantWide || organizationPublicIds.contains(value);
        }

        public boolean permitsWorkerAssignment(UUID workerId, UUID assignmentId) {
            return tenantWide
                    || (workerPublicIds.isEmpty() && assignmentPublicIds.isEmpty())
                    || workerPublicIds.contains(workerId)
                    || assignmentPublicIds.contains(assignmentId);
        }
    }
}
