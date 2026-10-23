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
                    java.util.Map.entry("predicate.hcm-organization-graph-authority.v1",
                            "HcmPopulationScopeService:organization-root-population"),
                    java.util.Map.entry("predicate.hcm-performance-cycle-object.v1",
                            "PerformanceCycleService:locked-cycle-object"),
                    java.util.Map.entry("predicate.hcm-performance-cycle-publish-sod.v1",
                            "PerformanceCycleService:publisher-maker-sod"),
                    java.util.Map.entry("predicate.hcm-performance-review-assignment.v1",
                            "PerformanceReviewAssignmentAuthority:reviewer-object-assignment"),
                    java.util.Map.entry("predicate.hcm-performance-feedback-request.v1",
                            "PerformanceFeedbackRequestAuthority:cycle-worker-assignment"),
                    java.util.Map.entry("predicate.hcm-performance-feedback-assignment.v1",
                            "PerformanceFeedbackAssignmentAuthority:provider-assignment"),
                    java.util.Map.entry("predicate.hcm-performance-feedback-release.v1",
                            "PerformanceFeedbackReleaseAuthority:released-subject-population"),
                    java.util.Map.entry("predicate.hcm-workforce-visible-person.v1",
                            "WorkforcePeopleAndOrganizationChart:population-filter"),
                    java.util.Map.entry("predicate.hcm-workforce-ingestion-scope.v1",
                            "WorkforceIngestionService:post-receipt-population"),
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
    private static final java.util.Set<String> ORGANIZATION_GRAPH_ROUTES =
            java.util.Set.of(
                    "route.hcm.operations.organization-graph.data",
                    "route.hcm.operations.organization-positions.page");
    private static final java.util.Set<String> HRM007_TEAM_ROUTES = java.util.Set.of(
            "route.hcm.team.people-changes.page",
            "route.hcm.team.people-changes.data");
    private static final java.util.Set<String> HRM007_OPERATIONS_ROUTES = java.util.Set.of(
            "route.hcm.operations.people-appointments.page",
            "route.hcm.operations.people-appointments.data");
    private static final java.util.Set<String> HRM007_CONFIGURATION_ROUTES = java.util.Set.of(
            "route.hcm.configuration.people-employment.page",
            "route.hcm.configuration.people-employment.data");
    private static final java.util.Set<String> HRM009_PERSONAL_QUERY_ROUTES = java.util.Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data");
    private static final java.util.Set<String> HRM009_OPERATIONS_QUERY_ROUTES = java.util.Set.of(
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data");
    private static final String HRM009_ACTION_ROUTE =
            "route.hcm.operations.certificate-issue.action";
    private static final String HRM012_ACTION_ROUTE =
            "route.hcm.operations.workforce-ingestion.action";
    private static final java.util.Set<String> PER008_TEAM_ROUTES = java.util.Set.of(
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data");
    private static final java.util.Set<String> PER008_ASSIGNED_COLLECTION_ROUTES =
            java.util.Set.of(
                    "route.hcm.personal.performance-feedback.page",
                    "route.hcm.personal.feedback-assignments-mine.data");
    private static final java.util.Set<String> PER008_REQUEST_ROUTES = java.util.Set.of(
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action");
    private static final String PER008_ASSIGNED_DETAIL_ROUTE =
            "route.hcm.personal.feedback-assignment-detail.data";
    private static final String PER008_ASSIGNED_SUBMIT_ROUTE =
            "route.hcm.personal.feedback-response-submit.action";
    private static final String PER008_RELEASE_ROUTE =
            "route.hcm.performance.feedback-release.data";

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
        if (ORGANIZATION_GRAPH_ROUTES.contains(route)) {
            validateOrganizationGraph(authority);
            return;
        }
        if (HRM007_TEAM_ROUTES.contains(route)) {
            validateHrm007(authority, "team-people-change-reader",
                    "hcm.team.people-change.view", "predicate.team-target-population.v1",
                    java.util.Set.of("TARGET_POPULATION"));
            HcmPopulationScopeService.ResolvedPopulation population = populations.requireTeam();
            populations.requireTrustedScope(population, "hcm.team", "TARGET_POPULATION",
                    "TEAM/ORG_UNIT",
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            return;
        }
        if (HRM007_OPERATIONS_ROUTES.contains(route)) {
            validateHrm007(authority, "people-operations-reader",
                    "hcm.operations.people.view",
                    "predicate.hcm-workforce-visible-person.v1",
                    java.util.Set.of("TARGET_POPULATION"));
            HcmPopulationScopeService.ResolvedPopulation population =
                    populations.requireOperations("READ");
            populations.requireTrustedScope(population, "hcm.operations", "TARGET_POPULATION",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
            return;
        }
        if (HRM007_CONFIGURATION_ROUTES.contains(route)) {
            validateHrm007(authority, "configuration-workbench-reader",
                    "hcm.configuration.workbench.view",
                    "predicate.hcm-configuration-scope.v1",
                    java.util.Set.of("CONFIG_SCOPE"));
            populations.requireConfigurationScope();
            return;
        }
        if (HRM009_PERSONAL_QUERY_ROUTES.contains(route)) {
            validateHrm009Query(authority, "self-request-reader",
                    "hcm.personal.requests.view", "predicate.self-person.v1",
                    java.util.Set.of("SELF"));
            populations.requireSelfScope("hcm.personal");
            return;
        }
        if (HRM009_OPERATIONS_QUERY_ROUTES.contains(route)) {
            validateHrm009Query(authority, "people-operations-reader",
                    "hcm.operations.people.view",
                    "predicate.hcm-workforce-visible-person.v1",
                    java.util.Set.of("TARGET_POPULATION"));
            HcmPopulationScopeService.ResolvedPopulation population =
                    populations.requireOperations("READ");
            populations.requireTrustedScope(
                    population, "hcm.operations", "TARGET_POPULATION",
                    "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                    "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
            return;
        }
        if (HRM009_ACTION_ROUTE.equals(route)) {
            validateHrm009Action(authority);
            return;
        }
        if (HRM012_ACTION_ROUTE.equals(route)) {
            validateHrm012ReceiptBoundary(authority);
            return;
        }
        if (PER008_TEAM_ROUTES.contains(route)) {
            validatePer008Team(authority);
            return;
        }
        if (PER008_ASSIGNED_COLLECTION_ROUTES.contains(route)) {
            validatePer008AssignedCollection(authority);
            return;
        }
        if (PER008_REQUEST_ROUTES.contains(route)) {
            validatePer008Request(authority);
            return;
        }
        if (PER008_ASSIGNED_DETAIL_ROUTE.equals(route)
                || PER008_ASSIGNED_SUBMIT_ROUTE.equals(route)) {
            validatePer008AssignedObject(authority);
            return;
        }
        if (PER008_RELEASE_ROUTE.equals(route)) {
            validatePer008Release(authority);
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

    private void validateHrm012ReceiptBoundary(HcmV3PepRegistry.RouteAuthority authority) {
        if (!"ACTION".equals(authority.routeKind())
                || !"workforce-ingestion-executor".equals(authority.profileKey())
                || authority.readOnly() || !"POST".equals(authority.method())
                || !"hcm.integration.workforce-ingestion.execute".equals(
                        authority.capabilityContractKey())
                || !java.util.Set.of(
                        "predicate.hcm-workforce-ingestion-scope.v1",
                        "predicate.people.object-version.v1")
                        .equals(authority.predicatePolicyKeys())
                || !java.util.Set.of("OBJECT", "TARGET_POPULATION")
                        .equals(authority.targetBindingKinds())) {
            throw new IllegalStateException(
                    "HRM-012 post-receipt scope profile changed.");
        }
        // No population lookup occurs at the marker boundary. The module owner invokes the
        // connector/legal-entity predicate only after receipt, Auth and metadata precedence.
    }

    private void validatePer008Team(HcmV3PepRegistry.RouteAuthority authority) {
        String expectedKind = authority.routeContractKey().endsWith(".page") ? "PAGE" : "DATA";
        if (!expectedKind.equals(authority.routeKind())
                || !"team-performance-reader".equals(authority.profileKey())
                || !authority.readOnly() || !"GET".equals(authority.method())
                || !"hcm.team.workbench.view".equals(authority.capabilityContractKey())
                || !java.util.Set.of("predicate.team-target-population.v1")
                .equals(authority.predicatePolicyKeys())
                || !java.util.Set.of("TARGET_POPULATION")
                .equals(authority.targetBindingKinds())) {
            throw new IllegalStateException("PER-008 team workbench scope profile changed.");
        }
        HcmPopulationScopeService.ResolvedPopulation population = populations.requireTeam();
        populations.requireTrustedScope(
                population, "hcm.team", "TARGET_POPULATION", "TEAM/ORG_UNIT",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
    }

    private void validatePer008AssignedCollection(
            HcmV3PepRegistry.RouteAuthority authority) {
        String expectedKind = authority.routeContractKey().endsWith(".page") ? "PAGE" : "DATA";
        if (!expectedKind.equals(authority.routeKind())
                || !"self-performance-reader".equals(authority.profileKey())
                || !authority.readOnly() || !"GET".equals(authority.method())
                || !"hcm.personal.performance.view".equals(
                authority.capabilityContractKey())
                || !java.util.Set.of(
                        "predicate.self-person.v1",
                        "predicate.hcm-performance-feedback-assignment.v1")
                .equals(authority.predicatePolicyKeys())
                || !java.util.Set.of("SELF", "TARGET_POPULATION")
                .equals(authority.targetBindingKinds())) {
            throw new IllegalStateException("PER-008 assigned collection scope profile changed.");
        }
        populations.requireSelfScope("hcm.personal");
    }

    private void validatePer008Request(HcmV3PepRegistry.RouteAuthority authority) {
        boolean action = authority.routeContractKey().endsWith(".action");
        if (!(action ? "ACTION" : "DATA").equals(authority.routeKind())
                || authority.readOnly() == action
                || !(action ? "POST" : "GET").equals(authority.method())) {
            throw new IllegalStateException("PER-008 request operation changed.");
        }
        if ("manager-feedback-requester".equals(authority.profileKey())
                && "hcm.team.performance.review".equals(
                authority.capabilityContractKey())
                && java.util.Set.of(
                        "predicate.team-target-population.v1",
                        "predicate.hcm-performance-feedback-request.v1")
                .equals(authority.predicatePolicyKeys())
                && java.util.Set.of("OBJECT", "TARGET_POPULATION")
                .equals(authority.targetBindingKinds())) {
            HcmPopulationScopeService.ResolvedPopulation population = populations.requireTeam();
            populations.requireTrustedScope(
                    population, "hcm.team", "TARGET_POPULATION", "TEAM/ORG_UNIT",
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            return;
        }
        if ("self-feedback-requester".equals(authority.profileKey())
                && "per.feedback.request.create".equals(
                authority.capabilityContractKey())
                && java.util.Set.of(
                        "predicate.self-person.v1",
                        "predicate.hcm-performance-feedback-request.v1")
                .equals(authority.predicatePolicyKeys())
                && java.util.Set.of("SELF", "OBJECT")
                .equals(authority.targetBindingKinds())) {
            populations.requireSelfScope("hcm.personal");
            return;
        }
        throw new IllegalStateException("PER-008 request scope profile changed.");
    }

    private void validatePer008AssignedObject(
            HcmV3PepRegistry.RouteAuthority authority) {
        boolean submit = PER008_ASSIGNED_SUBMIT_ROUTE.equals(authority.routeContractKey());
        String expectedPublicPath = submit
                ? "/api/people/v1/hris/performance/feedback-assignments/"
                + "{feedbackAssignmentId}/responses"
                : "/api/people/v1/hris/performance/feedback-assignments/"
                + "{feedbackAssignmentId}";
        if (!(submit ? "ACTION" : "DATA").equals(authority.routeKind())
                || authority.readOnly() == submit
                || !(submit ? "POST" : "GET").equals(authority.method())
                || !(submit ? "assigned-feedback-provider" : "assigned-feedback-reader")
                .equals(authority.profileKey())
                || !(submit ? "per.feedback.assigned.submit"
                : "hcm.personal.performance.view").equals(
                authority.capabilityContractKey())
                || !java.util.Set.of("predicate.hcm-performance-feedback-assignment.v1")
                .equals(authority.predicatePolicyKeys())
                || !java.util.Set.of("OBJECT", "TARGET_POPULATION")
                .equals(authority.targetBindingKinds())
                || !(authority.routeContractKey() + ".binding.01")
                .equals(authority.bindingKey())
                || !expectedPublicPath.equals(authority.publicPath())
                || authority.stepUpBinding() != null) {
            throw new IllegalStateException("PER-008 assigned object scope profile changed.");
        }
        // The module owner performs the final tenant/request/provider assignment and OPEN-window
        // proof. This central guard never interprets the URL assignment as provider authority.
    }

    private void validatePer008Release(HcmV3PepRegistry.RouteAuthority authority) {
        if (!"DATA".equals(authority.routeKind()) || !authority.readOnly()
                || !"GET".equals(authority.method())
                || !"per.feedback.aggregate.view".equals(
                authority.capabilityContractKey())
                || !java.util.Set.of("predicate.hcm-performance-feedback-release.v1")
                .equals(authority.predicatePolicyKeys())
                || authority.stepUpBinding() != null) {
            throw new IllegalStateException("PER-008 release scope profile changed.");
        }
        switch (authority.profileKey()) {
            case "self-feedback-release-reader" -> {
                // RouteAuthority carries the selected profile target kinds. The generated
                // predicate descriptor separately closes the union over SELF, OBJECT and
                // TARGET_POPULATION in HcmV3PepRegistry.validateClosure().
                if (!java.util.Set.of("SELF", "OBJECT")
                        .equals(authority.targetBindingKinds())) {
                    throw new IllegalStateException(
                            "PER-008 self release target binding changed.");
                }
                populations.requireSelfScope("hcm.personal");
            }
            case "manager-feedback-release-reader" -> {
                requirePer008ReleasePopulationTarget(authority);
                HcmPopulationScopeService.ResolvedPopulation population = populations.requireTeam();
                populations.requireTrustedScope(
                        population, "hcm.team", "TARGET_POPULATION", "TEAM/ORG_UNIT",
                        "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            }
            case "operator-feedback-release-reader" -> {
                requirePer008ReleasePopulationTarget(authority);
                HcmPopulationScopeService.ResolvedPopulation population =
                        populations.requireOperations("READ");
                populations.requireTrustedScope(
                        population, "hcm.operations", "TARGET_POPULATION",
                        "NAMED_POPULATION");
            }
            default -> throw new IllegalStateException(
                    "PER-008 release population profile changed.");
        }
    }

    private void requirePer008ReleasePopulationTarget(
            HcmV3PepRegistry.RouteAuthority authority) {
        if (!java.util.Set.of("OBJECT", "TARGET_POPULATION")
                .equals(authority.targetBindingKinds())) {
            throw new IllegalStateException(
                    "PER-008 population release target binding changed.");
        }
    }

    private void validateOrganizationGraph(HcmV3PepRegistry.RouteAuthority authority) {
        String expectedKind = authority.routeContractKey().endsWith(".data") ? "DATA" : "PAGE";
        if (!expectedKind.equals(authority.routeKind())
                || !"organization-viewer".equals(authority.profileKey())
                || !authority.readOnly()
                || !"GET".equals(authority.method())
                || !"hcm.operations.organization.view".equals(
                authority.capabilityContractKey())
                || !java.util.Set.of("predicate.hcm-organization-graph-authority.v1")
                .equals(authority.predicatePolicyKeys())
                || !java.util.Set.of("TARGET_POPULATION")
                .equals(authority.targetBindingKinds())) {
            throw new IllegalStateException("Organization graph scope profile changed.");
        }
        HcmPopulationScopeService.ResolvedPopulation population =
                populations.requireOperations("READ");
        populations.requireField(population, "ORGANIZATION");
        populations.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ORGANIZATION_TARGET_POPULATION");
    }

    private void validateHrm007(
            HcmV3PepRegistry.RouteAuthority authority,
            String profileKey,
            String capabilityKey,
            String predicateKey,
            java.util.Set<String> targetKinds) {
        String expectedKind = authority.routeContractKey().endsWith(".page") ? "PAGE" : "DATA";
        if (!expectedKind.equals(authority.routeKind())
                || !profileKey.equals(authority.profileKey())
                || !authority.readOnly()
                || !"GET".equals(authority.method())
                || !capabilityKey.equals(authority.capabilityContractKey())
                || !java.util.Set.of(predicateKey).equals(authority.predicatePolicyKeys())
                || !targetKinds.equals(authority.targetBindingKinds())) {
            throw new IllegalStateException("HRM-007 entry-query scope profile changed.");
        }
    }

    private void validateHrm009Query(
            HcmV3PepRegistry.RouteAuthority authority,
            String profileKey,
            String capabilityKey,
            String predicateKey,
            java.util.Set<String> targetKinds) {
        String expectedKind = authority.routeContractKey().endsWith(".page") ? "PAGE" : "DATA";
        if (!expectedKind.equals(authority.routeKind())
                || !profileKey.equals(authority.profileKey())
                || !authority.readOnly()
                || !"GET".equals(authority.method())
                || !capabilityKey.equals(authority.capabilityContractKey())
                || !java.util.Set.of(predicateKey).equals(authority.predicatePolicyKeys())
                || !targetKinds.equals(authority.targetBindingKinds())
                || authority.stepUpBinding() != null) {
            throw new IllegalStateException("HRM-009 entry-query scope profile changed.");
        }
    }

    private void validateHrm009Action(HcmV3PepRegistry.RouteAuthority authority) {
        HcmV3PepRegistry.StepUpBinding stepUp = authority.stepUpBinding();
        if (!"ACTION".equals(authority.routeKind())
                || authority.readOnly()
                || !"POST".equals(authority.method())
                || !"STEPUP-MGMT-HIGH-V1".equals(authority.activationPolicy())
                || stepUp == null
                || !"PERSON".equals(stepUp.targetType())
                || stepUp.targetIdPathParameter() != null
                || !java.util.List.of("personPublicId").equals(stepUp.targetIdBodyFields())
                || !"people".equals(stepUp.ownerServiceKey())
                || !"dwp-people-server".equals(stepUp.audience())) {
            throw new IllegalStateException("HRM-009 route-wide HIGH binding changed.");
        }
        if ("certificate-self-requester".equals(authority.profileKey())
                && "hcm.personal.certificate.request".equals(
                authority.capabilityContractKey())
                && java.util.Set.of("predicate.self-person.v1",
                "predicate.people.object-version.v1").equals(
                authority.predicatePolicyKeys())
                && java.util.Set.of("SELF", "OBJECT").equals(
                authority.targetBindingKinds())) {
            // The command owner revalidates hcm.operations only after durable
            // replay disposition. Do not make a replay depend on mutable scope.
            return;
        }
        if ("certificate-operations-issuer".equals(authority.profileKey())
                && "hcm.operations.certificate.issue".equals(
                authority.capabilityContractKey())
                && java.util.Set.of("predicate.hcm-workforce-visible-person.v1",
                "predicate.people.object-version.v1").equals(
                authority.predicatePolicyKeys())
                && java.util.Set.of("OBJECT", "TARGET_POPULATION").equals(
                authority.targetBindingKinds())) {
            // CertificateAuthority performs the locked live-population proof for
            // a fresh command after replay disposition and before mutation.
            return;
        }
        throw new IllegalStateException(
                "HRM-009 action profile changed or TEAM fallback was introduced.");
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
