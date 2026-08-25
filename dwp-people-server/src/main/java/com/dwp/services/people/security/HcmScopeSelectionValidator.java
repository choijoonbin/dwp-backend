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
                    java.util.Map.entry("predicate.hcm-domain-target-population.v1",
                            "HcmPopulationRepository:domain-population"),
                    java.util.Map.entry("predicate.hcm-export-population.v1",
                            "WorkforceExportService:locked-export-population"),
                    java.util.Map.entry("predicate.hcm-integration-nonsecret-update.v1",
                            "HrisImportService:nonsecret-command"),
                    java.util.Map.entry("predicate.hcm-org-approval-sod.v1",
                            "OrganizationScenarioDecisionService:approver-maker-sod"),
                    java.util.Map.entry("predicate.hcm-org-publish-sod.v1",
                            "OrganizationScenarioService:publisher-maker-sod"),
                    java.util.Map.entry("predicate.hcm-workforce-visible-person.v1",
                            "WorkforcePeopleAndOrganizationChart:population-filter"),
                    java.util.Map.entry("predicate.people.object-version.v1",
                            "OwnerMutationServices:locked-object-version"),
                    java.util.Map.entry("predicate.self-person.v1",
                            "PeopleDirectoryService:self-public-id"),
                    java.util.Map.entry("predicate.team-target-population.v1",
                            "HcmPopulationRepository:locked-team-population"));

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

    static java.util.Map<String, String> ownerPredicateClosure() {
        return OWNER_PREDICATE_CLOSURE;
    }
}
