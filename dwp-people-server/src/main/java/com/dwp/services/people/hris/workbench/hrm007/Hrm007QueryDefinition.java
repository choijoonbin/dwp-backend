package com.dwp.services.people.hris.workbench.hrm007;

import java.util.List;
import java.util.Set;

/** Closed owner-query registry for BASE-TFR-HRM-007. */
public enum Hrm007QueryDefinition {

    PEOPLE_CHANGES(
            "IAQ-005",
            "IAQ.OP.005",
            "PEOPLE_CHANGES",
            "/v1/hris/workbench/team/people-changes",
            "/api/people/v1/hris/workbench/team/people-changes",
            "/hr/team/people-changes",
            "hcm.team.people-change.view",
            "HRIS_LINE_MANAGER",
            "HRIS_DUTY_MANAGER_PEOPLE_CHANGE_READER",
            "HR_LIFECYCLE",
            Set.of("DIRECT_REPORTS", "APPROVED_DELEGATION"),
            Set.of("TARGET_POPULATION"),
            Set.of("predicate.team-target-population.v1"),
            List.of("CHANGE_REQUEST_SUMMARY", "LIMITED_EMPLOYMENT", "APPROVAL_STATUS"),
            Set.of(
                    "changeRequestSummary:ASC", "changeRequestSummary:DESC",
                    "limitedEmployment:ASC", "limitedEmployment:DESC",
                    "approvalStatus:ASC", "approvalStatus:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC")),
    APPOINTMENTS(
            "IAQ-007",
            "IAQ.OP.007",
            "APPOINTMENTS",
            "/v1/hris/workbench/operations/appointments",
            "/api/people/v1/hris/workbench/operations/appointments",
            "/hr/operations/people/appointments",
            "hcm.operations.people.view",
            "HRIS_HR_BUSINESS_PARTNER",
            "HRIS_DUTY_CORE_VIEWER",
            "HR_LIFECYCLE",
            Set.of("LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION"),
            Set.of("TARGET_POPULATION"),
            Set.of("predicate.hcm-workforce-visible-person.v1"),
            List.of("APPOINTMENT", "ASSIGNMENT", "EMPLOYMENT"),
            Set.of(
                    "appointment:ASC", "appointment:DESC",
                    "assignment:ASC", "assignment:DESC",
                    "employment:ASC", "employment:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC")),
    PEOPLE_EMPLOYMENT(
            "IAQ-013",
            "IAQ.OP.013",
            "PEOPLE_EMPLOYMENT",
            "/v1/hris/workbench/configuration/people-employment",
            "/api/people/v1/hris/workbench/configuration/people-employment",
            "/hr/settings/people-employment",
            "hcm.configuration.workbench.view",
            "HRIS_CONFIG_AUTHOR",
            "HRIS_DUTY_CONFIGURATION_WORKBENCH_READER",
            "HRIS_CONFIGURATION",
            Set.of("CONFIG_SCOPE", "POLICY_NODE", "LEGAL_ENTITY"),
            Set.of("CONFIG_SCOPE"),
            Set.of("predicate.hcm-configuration-scope.v1"),
            List.of("POLICY_METADATA", "EMPLOYMENT_RULE", "IMPACT_SUMMARY"),
            Set.of(
                    "policyMetadata:ASC", "policyMetadata:DESC",
                    "employmentRule:ASC", "employmentRule:DESC",
                    "impactSummary:ASC", "impactSummary:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC"));

    private final String queryId;
    private final String operationId;
    private final String variantKey;
    private final String ownerPath;
    private final String browserPath;
    private final String deepLinkRoute;
    private final String capability;
    private final String profile;
    private final String duty;
    private final String purpose;
    private final Set<String> populations;
    private final Set<String> targetBindingKinds;
    private final Set<String> predicatePolicyKeys;
    private final List<String> fieldGroups;
    private final Set<String> sorts;

    Hrm007QueryDefinition(
            String queryId,
            String operationId,
            String variantKey,
            String ownerPath,
            String browserPath,
            String deepLinkRoute,
            String capability,
            String profile,
            String duty,
            String purpose,
            Set<String> populations,
            Set<String> targetBindingKinds,
            Set<String> predicatePolicyKeys,
            List<String> fieldGroups,
            Set<String> sorts) {
        this.queryId = queryId;
        this.operationId = operationId;
        this.variantKey = variantKey;
        this.ownerPath = ownerPath;
        this.browserPath = browserPath;
        this.deepLinkRoute = deepLinkRoute;
        this.capability = capability;
        this.profile = profile;
        this.duty = duty;
        this.purpose = purpose;
        this.populations = Set.copyOf(populations);
        this.targetBindingKinds = Set.copyOf(targetBindingKinds);
        this.predicatePolicyKeys = Set.copyOf(predicatePolicyKeys);
        this.fieldGroups = List.copyOf(fieldGroups);
        this.sorts = Set.copyOf(sorts);
    }

    public String queryId() {
        return queryId;
    }

    public String operationId() {
        return operationId;
    }

    public String variantKey() {
        return variantKey;
    }

    public String ownerPath() {
        return ownerPath;
    }

    public String browserPath() {
        return browserPath;
    }

    public String deepLinkRoute() {
        return deepLinkRoute;
    }

    public String capability() {
        return capability;
    }

    public String profile() {
        return profile;
    }

    public String duty() {
        return duty;
    }

    public String purpose() {
        return purpose;
    }

    public Set<String> populations() {
        return populations;
    }

    public Set<String> targetBindingKinds() {
        return targetBindingKinds;
    }

    public Set<String> predicatePolicyKeys() {
        return predicatePolicyKeys;
    }

    public List<String> fieldGroups() {
        return fieldGroups;
    }

    public Set<String> sorts() {
        return sorts;
    }
}
