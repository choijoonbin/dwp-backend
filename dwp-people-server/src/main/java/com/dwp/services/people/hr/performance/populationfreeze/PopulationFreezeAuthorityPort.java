package com.dwp.services.people.hr.performance.populationfreeze;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
            TargetPopulation targetPopulation,
            Map<String, FieldDecision> fieldDecisions,
            long fieldPolicyRevision,
            long authorizationRevision) {

        public AuthorityEvidence {
            fieldDecisions = fieldDecisions == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(fieldDecisions));
        }
    }

    /** Owner-resolved predicate material; a digest alone is never sufficient to filter rows. */
    record TargetPopulation(
            boolean tenantWide,
            String managerAssignmentRef,
            Set<UUID> organizationRefs,
            Set<String> fieldGroups,
            String revision) {

        public TargetPopulation {
            organizationRefs = organizationRefs == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(organizationRefs));
            fieldGroups = fieldGroups == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(fieldGroups));
        }

        boolean permits(UUID organizationRef, UUID reviewerAssignmentRef) {
            return tenantWide
                    || organizationRefs.contains(organizationRef)
                    || (managerAssignmentRef != null
                    && reviewerAssignmentRef != null
                    && managerAssignmentRef.equals(reviewerAssignmentRef.toString()));
        }
    }

    enum FieldDecision {
        VIEW,
        MASK,
        OMIT
    }
}
