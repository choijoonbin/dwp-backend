package com.dwp.services.people.security;

import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.springframework.stereotype.Component;

/** Validates the selected owner-derived scope for every generated HCM plane. */
@Component
final class HcmScopeSelectionValidator {

    private static final java.util.Map<String, String> OWNER_PREDICATE_CLOSURE =
            java.util.Map.ofEntries(
                    java.util.Map.entry("predicate.directory-visible-person.v1",
                            "PeopleDirectoryService:tenant+directory-policy"),
                    java.util.Map.entry("predicate.hcm-configuration-scope.v1",
                            "HcmPopulationScopeService:configuration-scope"),
                    java.util.Map.entry("predicate.hcm-compensation-planning-authority.v1",
                            "HcmCompensationPlanningAuthorityAdapter:locked-plan-authority"),
                    java.util.Map.entry("predicate.hcm-domain-target-population.v1",
                            "HcmPopulationRepository:domain-population"),
                    java.util.Map.entry("predicate.hcm-employee-change-authority.v1",
                            "EmployeeChangeRequestService:locked-request-authority"),
                    java.util.Map.entry("predicate.hcm-export-population.v1",
                            "WorkforceExportService:locked-export-population"),
                    java.util.Map.entry("predicate.hcm-integration-nonsecret-update.v1",
                            "HrisImportService:nonsecret-command"),
                    java.util.Map.entry("predicate.hcm-originating-command-receipt.v1",
                            "HrisExperienceCommandReceiptService:sealed-originating-command"),
                    java.util.Map.entry("predicate.hcm-org-approval-sod.v1",
                            "OrganizationScenarioDecisionService:approver-maker-sod"),
                    java.util.Map.entry("predicate.hcm-org-publish-sod.v1",
                            "OrganizationScenarioService:publisher-maker-sod"),
                    java.util.Map.entry("predicate.hcm-performance-cycle-object.v1",
                            "PerformanceCycleService:locked-cycle-object"),
                    java.util.Map.entry("predicate.hcm-performance-cycle-publish-sod.v1",
                            "PerformanceCycleService:publisher-maker-sod"),
                    java.util.Map.entry("predicate.hcm-performance-review-assignment.v1",
                            "PerformanceReviewAssignmentAuthority:reviewer-object-assignment"),
                    java.util.Map.entry("predicate.hcm-workforce-visible-person.v1",
                            "WorkforcePeopleAndOrganizationChart:population-filter"),
                    java.util.Map.entry("predicate.people.object-version.v1",
                            "OwnerMutationServices:locked-object-version"),
                    java.util.Map.entry("predicate.self-person.v1",
                            "PeopleDirectoryService:self-public-id"),
                    java.util.Map.entry("predicate.team-target-population.v1",
                            "HcmPopulationRepository:locked-team-population"));

    private static final String RECEIPT_ROUTE =
            "route.hcm.operations.assignment-command-receipt.data";
    private static final java.util.Set<String> EMPLOYEE_CHANGE_MUTATION_ROUTES =
            java.util.Set.of(
                    "route.hcm.operations.employee-change-request-create.action",
                    "route.hcm.operations.employee-change-request-submit.action");

    private final HcmPopulationScopeService populations;

    HcmScopeSelectionValidator(HcmPopulationScopeService populations) {
        this.populations = populations;
    }

    void validate(HcmV3PepRegistry.RouteAuthority authority) {
        if (!OWNER_PREDICATE_CLOSURE.keySet().containsAll(
                authority.predicatePolicyKeys())) {
            throw new IllegalStateException(
                    "Generated HCM route declares an unsupported owner predicate.");
        }
        String route = authority.routeContractKey();
        if (RECEIPT_ROUTE.equals(route)) {
            if (!"DATA".equals(authority.routeKind())
                    || !"originating-command".equals(authority.profileKey())
                    || !authority.readOnly()
                    || !"GET".equals(authority.method())
                    || !"hcm.command-receipt.view".equals(
                    authority.capabilityContractKey())
                    || !java.util.Set.of("predicate.hcm-originating-command-receipt.v1")
                    .equals(authority.predicatePolicyKeys())
                    || !java.util.Set.of("OBJECT", "TARGET_POPULATION")
                    .equals(authority.targetBindingKinds())) {
                throw new IllegalStateException(
                        "Originating-command receipt scope profile changed.");
            }
            // This scope is deliberately neutral. The receipt owner loads the sealed row and
            // then re-proves the exact original action and live People population.
            return;
        }
        if (EMPLOYEE_CHANGE_MUTATION_ROUTES.contains(route)) {
            validateEmployeeChangeMutation(authority);
            return;
        }
        if (java.util.Set.of(
                "route.hcm.performance.assigned-review.data",
                "route.hcm.performance.assigned-review-draft-update.action",
                "route.hcm.performance.assigned-review-submit.action").contains(route)) {
            if (!java.util.Set.of("predicate.hcm-performance-review-assignment.v1")
                    .equals(authority.predicatePolicyKeys())) {
                throw new IllegalStateException(
                        "Assigned review route predicate closure changed.");
            }
            if ("self-reviewer".equals(authority.profileKey())
                    && "per.review.self.submit".equals(
                    authority.capabilityContractKey())
                    && java.util.Set.of("SELF", "OBJECT")
                    .equals(authority.targetBindingKinds())) {
                populations.requireSelfScope("hcm.operations");
                return;
            }
            if ("assigned-reviewer".equals(authority.profileKey())
                    && "hcm.team.performance.review".equals(
                    authority.capabilityContractKey())
                    && java.util.Set.of("OBJECT", "TARGET_POPULATION")
                    .equals(authority.targetBindingKinds())) {
                HcmPopulationScopeService.ResolvedPopulation population =
                        populations.requireOperations("READ");
                populations.requireTrustedScope(
                        population, "hcm.operations", "TARGET_POPULATION",
                        "ASSIGNED_REVIEW_POPULATION");
                // The owner predicate remains the final reviewId assignment check; a URL
                // reviewId is never treated as reviewer identity evidence.
                return;
            }
            throw new IllegalStateException("Assigned review scope profile changed.");
        }
        if (route.startsWith("route.hcm.personal.")) {
            populations.requireSelfScope();
            return;
        }
        if (route.startsWith("route.hcm.team.")) {
            HcmPopulationScopeService.ResolvedPopulation population = populations.requireTeam();
            populations.requireTrustedScope(
                    population, "hcm.team", "TARGET_POPULATION",
                    "TEAM/ORG_UNIT",
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            return;
        }
        if (route.startsWith("route.hcm.operations.")) {
            HcmPopulationScopeService.ResolvedPopulation population =
                    populations.requireOperations("READ");
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION",
                    "TIME_TARGET_POPULATION", "ABSENCE_TARGET_POPULATION",
                    "BENEFITS_TARGET_POPULATION", "PAY_TARGET_POPULATION",
                    "TALENT_TARGET_POPULATION");
            return;
        }
        if (route.startsWith("route.hcm.management.controlled-export")) {
            HcmPopulationScopeService.ResolvedPopulation population =
                    populations.requireOperations("EXPORT");
            populations.requireTrustedScope(
                    population, "hcm.management", "TARGET_POPULATION",
                    "APPROVED_EXPORT_POPULATION");
            return;
        }
        if (route.startsWith("route.hcm.management.")) {
            populations.requireConfigurationScope();
            return;
        }
        throw new IllegalStateException("Unknown generated HCM route plane.");
    }

    private void validateEmployeeChangeMutation(HcmV3PepRegistry.RouteAuthority authority) {
        boolean submit = authority.routeContractKey().endsWith("-submit.action");
        if (!"ACTION".equals(authority.routeKind()) || authority.readOnly()
                || !"POST".equals(authority.method())
                || !java.util.Set.of("predicate.hcm-employee-change-authority.v1")
                .equals(authority.predicatePolicyKeys())) {
            throw new IllegalStateException("Employee-change mutation profile changed.");
        }
        if ("self-service".equals(authority.profileKey())
                && "hcm.personal.profile.update".equals(
                authority.capabilityContractKey())
                && (submit ? java.util.Set.of("SELF", "OBJECT")
                : java.util.Set.of("SELF")).equals(authority.targetBindingKinds())) {
            populations.requireSelfScope("hcm.operations");
            return;
        }
        if ("team-management".equals(authority.profileKey())
                && "hcm.team.people-change.request".equals(
                authority.capabilityContractKey())
                && (submit
                ? java.util.Set.of("RELATIONSHIP", "OBJECT", "TARGET_POPULATION")
                : java.util.Set.of("RELATIONSHIP", "TARGET_POPULATION"))
                .equals(authority.targetBindingKinds())) {
            HcmPopulationScopeService.ResolvedPopulation population = populations.requireTeam();
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "TEAM/ORG_UNIT",
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            return;
        }
        if ("full-management".equals(authority.profileKey())
                && "hcm.operations.people.update".equals(
                authority.capabilityContractKey())
                && (submit ? java.util.Set.of("OBJECT", "TARGET_POPULATION")
                : java.util.Set.of("TARGET_POPULATION"))
                .equals(authority.targetBindingKinds())) {
            HcmPopulationScopeService.ResolvedPopulation population =
                    populations.requireOperations("READ");
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
            return;
        }
        throw new IllegalStateException("Employee-change scope profile changed.");
    }

    static java.util.Map<String, String> ownerPredicateClosure() {
        return OWNER_PREDICATE_CLOSURE;
    }
}
