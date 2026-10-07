package com.dwp.services.people.workforce;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenant/effective-date seam for People 360 role classification and field projection.
 * Implementations must return one decision for every field in {@link #FIELD_REGISTRY}.
 */
public interface People360ProjectionPolicyProvider {

    List<String> FIELD_REGISTRY = List.of(
            "person.displayName",
            "person.preferredLocale",
            "person.timeZone",
            "person.lifecycleState",
            "employment.workerNumber",
            "employment.workerType",
            "employment.workerStatus",
            "employment.originalHireDate",
            "employment.relationshipType",
            "employment.relationshipStartDate",
            "employment.relationshipEndDate",
            "employment.legalEmployerName",
            "primaryAssignment.assignmentKey",
            "primaryAssignment.assignmentStatus",
            "primaryAssignment.businessTitle",
            "primaryAssignment.organizationName",
            "primaryAssignment.jobProfileName",
            "primaryAssignment.jobGradeName",
            "primaryAssignment.locationName",
            "primaryAssignment.managerDisplayName",
            "primaryAssignment.effectiveStartDate",
            "primaryAssignment.effectiveEndDate");

    People360Dtos.Archetype operationsArchetype(Subject subject);

    ProjectionPolicy resolve(PolicyInput input);

    record Subject(Set<String> roles, Set<String> permissions) {
        public Subject {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    record PolicyInput(
            Long tenantId,
            LocalDate asOf,
            ProjectionPurpose purpose,
            People360Dtos.Archetype archetype,
            String authorityRevision,
            Set<String> fieldGroups) {

        public PolicyInput {
            fieldGroups = fieldGroups == null ? Set.of() : Set.copyOf(fieldGroups);
        }
    }

    enum ProjectionPurpose {
        LIST,
        DETAIL
    }

    record ProjectionPolicy(
            String providerRevision,
            Map<String, People360Dtos.FieldDecisionValue> fieldDecisions) {

        public ProjectionPolicy {
            fieldDecisions = fieldDecisions == null
                    ? Map.of() : Map.copyOf(fieldDecisions);
        }
    }
}
