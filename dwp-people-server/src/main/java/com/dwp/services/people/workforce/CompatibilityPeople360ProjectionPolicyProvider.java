package com.dwp.services.people.workforce;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Safe compatibility policy until tenant-authored projection policies are persisted.
 * This is deliberately a replaceable provider, not a final enterprise policy.
 */
@Component
public class CompatibilityPeople360ProjectionPolicyProvider
        implements People360ProjectionPolicyProvider {

    static final String PROVIDER_REVISION = "people360.compatibility-default.v1";

    private static final Set<String> AUDITOR_ROLES =
            Set.of("AUDITOR", "HR_AUDITOR", "WORKFORCE_AUDITOR", "COMPLIANCE_AUDITOR");

    @Override
    public People360Dtos.Archetype operationsArchetype(Subject subject) {
        boolean auditor = subject.permissions().contains("DATA.WORKFORCE:AUDIT")
                || subject.roles().stream().anyMatch(AUDITOR_ROLES::contains);
        return auditor
                ? People360Dtos.Archetype.AUDITOR
                : People360Dtos.Archetype.HR_OPERATOR;
    }

    @Override
    public ProjectionPolicy resolve(PolicyInput input) {
        if (input.tenantId() == null || input.tenantId() <= 0
                || input.asOf() == null || input.purpose() == null
                || input.archetype() == null
                || input.authorityRevision() == null
                || input.authorityRevision().isBlank()) {
            return new ProjectionPolicy(PROVIDER_REVISION, Map.of());
        }
        Map<String, People360Dtos.FieldDecisionValue> values = new LinkedHashMap<>();
        if (input.purpose() == ProjectionPurpose.LIST) {
            FIELD_REGISTRY.forEach(field -> values.put(
                    field, People360Dtos.FieldDecisionValue.OMIT));
            view(values,
                    "person.displayName",
                    "person.lifecycleState");
            if (input.fieldGroups().contains("EMPLOYMENT")) {
                view(values,
                        "employment.workerStatus",
                        "primaryAssignment.businessTitle",
                        "primaryAssignment.organizationName",
                        "primaryAssignment.jobProfileName");
            }
            return new ProjectionPolicy(PROVIDER_REVISION, values);
        }
        FIELD_REGISTRY.forEach(field -> values.put(
                field, People360Dtos.FieldDecisionValue.VIEW));
        switch (input.archetype()) {
            case SELF -> { }
            case MANAGER -> {
                omit(values, "person.preferredLocale", "employment.originalHireDate",
                        "primaryAssignment.assignmentKey",
                        "primaryAssignment.jobGradeName");
                if (input.fieldGroups().contains("EMPLOYMENT")) {
                    values.put(
                            "employment.workerNumber",
                            People360Dtos.FieldDecisionValue.MASK);
                } else {
                    omitEmployment(values);
                }
            }
            case HR_OPERATOR, AUDITOR -> workforce(values, input);
        }
        return new ProjectionPolicy(PROVIDER_REVISION, values);
    }

    private void workforce(
            Map<String, People360Dtos.FieldDecisionValue> values,
            PolicyInput input) {
        values.put("person.preferredLocale", People360Dtos.FieldDecisionValue.OMIT);
        if (input.archetype() == People360Dtos.Archetype.AUDITOR) {
            values.put("person.timeZone", People360Dtos.FieldDecisionValue.OMIT);
        }
        if (!input.fieldGroups().contains("EMPLOYMENT")) {
            omitEmployment(values);
            return;
        }
        boolean identifiers = input.fieldGroups().contains("WORKER_IDENTIFIERS");
        values.put("employment.workerNumber",
                identifiers && input.archetype() == People360Dtos.Archetype.HR_OPERATOR
                        ? People360Dtos.FieldDecisionValue.VIEW
                        : People360Dtos.FieldDecisionValue.MASK);
        values.put("primaryAssignment.assignmentKey",
                identifiers && input.archetype() == People360Dtos.Archetype.HR_OPERATOR
                        ? People360Dtos.FieldDecisionValue.VIEW
                        : People360Dtos.FieldDecisionValue.OMIT);
        if (!input.fieldGroups().contains("JOB_GRADE")) {
            values.put(
                    "primaryAssignment.jobGradeName",
                    People360Dtos.FieldDecisionValue.OMIT);
        }
    }

    private void omit(
            Map<String, People360Dtos.FieldDecisionValue> decisions,
            String... fields) {
        for (String field : fields) {
            decisions.put(field, People360Dtos.FieldDecisionValue.OMIT);
        }
    }

    private void view(
            Map<String, People360Dtos.FieldDecisionValue> decisions,
            String... fields) {
        for (String field : fields) {
            decisions.put(field, People360Dtos.FieldDecisionValue.VIEW);
        }
    }

    private void omitEmployment(
            Map<String, People360Dtos.FieldDecisionValue> decisions) {
        decisions.replaceAll((field, decision) -> field.startsWith("employment.")
                || field.startsWith("primaryAssignment.")
                ? People360Dtos.FieldDecisionValue.OMIT : decision);
    }
}
