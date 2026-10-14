package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HcmV3PepRegistryTest {

    private final HcmV3PepRegistry registry =
            new HcmV3PepRegistry(new ObjectMapper().findAndRegisterModules());

    @Test
    void loadsTheExactV42PeopleClosure() {
        assertThat(registry.bindingContracts()).hasSize(124);
        assertThat(registry.bindingContracts().stream()
                .map(HcmV3PepRegistry.BindingContract::routeContractKey)
                .distinct()).hasSize(97);
        assertThat(registry.bindingContracts())
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.team.home.page")
                        && value.servicePath().equals("/v1/hr/team"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.operations.overview.page")
                        && value.servicePath()
                        .equals("/v1/workforce/operations/overview"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.management.org-design.page")
                        && value.servicePath()
                        .equals("/v1/workforce/organization/candidates"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.operations.assignment-proposal-submit.action")
                        && value.servicePath().equals(
                        "/v1/workforce/assignment-proposals/{proposalId}/submit"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.operations.employee-change-request-apply.action")
                        && value.servicePath().equals(
                        "/v1/hris/employee-change-requests/{requestPublicId}/apply"));
        assertThat(registry.highRiskBindingContracts())
                .hasSize(14)
                .extracting(HcmV3PepRegistry.BindingContract::routeContractKey)
                .containsExactlyInAnyOrder(
                        "route.hcm.management.org-publish.action",
                        "route.hcm.management.controlled-export-create.action",
                        "route.hcm.management.controlled-export-retry.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.operations.performance-cycle-publish.action",
                        "route.hcm.operations.performance-population-freeze.action",
                        "route.hcm.operations.compensation-plan-approve.action",
                        "route.hcm.operations.compensation-snapshot-publish.action",
                        "route.hcm.operations.assignment-proposal-submit.action",
                        "route.hcm.operations.employee-change-request-apply.action",
                        "route.hcm.operations.assignment-event-publish.action");
        assertThat(HcmScopeSelectionValidator.ownerPredicateClosure())
                .hasSize(17)
                .containsOnlyKeys(
                        "predicate.directory-visible-person.v1",
                        "predicate.hcm-configuration-scope.v1",
                        "predicate.hcm-compensation-planning-authority.v1",
                        "predicate.hcm-domain-target-population.v1",
                        "predicate.hcm-export-population.v1",
                        "predicate.hcm-integration-nonsecret-update.v1",
                        "predicate.hcm-originating-command-receipt.v1",
                        "predicate.hcm-employee-change-authority.v1",
                        "predicate.hcm-org-approval-sod.v1",
                        "predicate.hcm-org-publish-sod.v1",
                        "predicate.hcm-performance-cycle-object.v1",
                        "predicate.hcm-performance-cycle-publish-sod.v1",
                        "predicate.hcm-performance-review-assignment.v1",
                        "predicate.hcm-workforce-visible-person.v1",
                        "predicate.people.object-version.v1",
                        "predicate.self-person.v1",
                        "predicate.team-target-population.v1");
    }

    @Test
    void assignedReviewRoutesKeepSelfAndAssignedIdentitySeparate() {
        String reviewId = "00000000-0000-0000-0000-000000000042";
        HcmV3PepRegistry.Decision assigned = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hris/performance/reviews/" + reviewId,
                        Set.of("APP.HCM:VIEW", "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE"),
                        null, "NORMAL", Set.of(),
                        "route.hcm.performance.assigned-review.data", null,
                        "PERFORMANCE_REVIEW", "TARGET_POPULATION"));
        assertThat(assigned.allowed()).isTrue();
        assertThat(assigned.authority().profileKey()).isEqualTo("assigned-reviewer");
        assertThat(assigned.authority().capabilityContractKey())
                .isEqualTo("hcm.team.performance.review");
        assertThat(assigned.authority().targetBindingKinds())
                .containsExactlyInAnyOrder("OBJECT", "TARGET_POPULATION");

        HcmV3PepRegistry.Decision self = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "PATCH", "/v1/hris/performance/reviews/" + reviewId,
                        Set.of("APP.HCM:VIEW", "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE"),
                        null, "NORMAL", Set.of(),
                        "route.hcm.performance.assigned-review-draft-update.action", null,
                        "PERFORMANCE_REVIEW", "SELF"));
        assertThat(self.allowed()).isTrue();
        assertThat(self.authority().profileKey()).isEqualTo("self-reviewer");
        assertThat(self.authority().capabilityContractKey())
                .isEqualTo("per.review.self.submit");
        assertThat(self.authority().predicatePolicyKeys())
                .containsExactly("predicate.hcm-performance-review-assignment.v1");

        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hris/performance/reviews/" + reviewId,
                Set.of("APP.HCM:VIEW", "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE"),
                null, "NORMAL", Set.of(),
                "route.hcm.performance.assigned-review.data", null,
                "CLIENT_FORGED", "TARGET_POPULATION")).denialCode())
                .isEqualTo("TRUSTED_PURPOSE_REQUIRED");
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hris/performance/reviews/" + reviewId,
                Set.of("APP.HCM:VIEW", "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE"),
                null, "NORMAL", Set.of(),
                "route.hcm.performance.assigned-review.data", null,
                "PERFORMANCE_REVIEW", "NAMED_POPULATION")).allowed()).isFalse();
    }

    @Test
    void iaq022RemainsAnOperatorWorkbenchAndCannotBecomeReviewIdentity() {
        HcmV3PepRegistry.Decision decision = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hris/performance/workbench/multirater-surveys",
                        Set.of("APP.HCM:VIEW", "DATA.HR_TALENT:VIEW"),
                        null, "NORMAL", Set.of(),
                        "route.hcm.operations.performance-multirater-surveys.page", null,
                        "PERFORMANCE_OPERATIONS", "TARGET_POPULATION"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authority().profileKey())
                .isEqualTo("performance-operator-reader");
        assertThat(decision.authority().capabilityContractKey())
                .isEqualTo("hcm.operations.performance.view");
        assertThat(decision.authority().predicatePolicyKeys())
                .containsExactly("predicate.hcm-domain-target-population.v1");
        assertThat(decision.authority().targetBindingKinds())
                .containsExactly("TARGET_POPULATION");
    }

    @Test
    void broadClaimsInvalidQueryButExactAuthorizationDeniesIt() {
        assertThat(registry.owns(
                "GET", "/v1/workforce/people", "view=other")).isTrue();

        HcmV3PepRegistry.Decision decision = registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/workforce/people", Set.of("DATA.WORKFORCE:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.operations.people.page", "view=other"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denialCode()).isEqualTo("UNKNOWN_METHOD_PATH_BINDING");
    }

    @Test
    void broadClaimsWrongMethodAndFixedPathButExactAuthorizationDeniesThem() {
        assertThat(registry.owns("DELETE", "/v1/hr/team", null)).isTrue();
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "DELETE", "/v1/hr/team", Set.of("APP.HCM:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.team.home.page", null)).allowed()).isFalse();

        assertThat(registry.owns("GET", "/v1/hr/operations/PAY", null)).isTrue();
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hr/operations/PAY", Set.of("DATA.HR_BENEFITS:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.operations.benefits.page", null)).allowed())
                .isFalse();
    }

    @Test
    void encodedSeparatorCannotEscapeBySkippingThePep() {
        String path = "/v1/hr/team/time/00000000-0000-0000-0000-000000000000%2fother/decision";
        assertThat(registry.owns("POST", path, null)).isTrue();
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "POST", path, Set.of("DATA.HR_TIME:APPROVE"), null,
                "NORMAL", Set.of(), "route.hcm.team.time-decision.action", null)).allowed())
                .isFalse();
    }

    @Test
    void encodedOrDoubleEncodedHcmPrefixIsClaimedButNeverAuthorized() {
        for (String path : Set.of(
                "/%76%31/%68%72/home",
                "/%2576%2531/%2568%2572/home")) {
            assertThat(registry.owns("GET", path, null)).isTrue();
            assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                    "GET", path, Set.of("APP.HCM:VIEW"), null,
                    "NORMAL", Set.of(), "route.hcm.personal.home.page", null)).allowed())
                    .isFalse();
        }
    }

    @Test
    void matrixParametersAreClaimedAndThenDeniedByExactAuthorization() {
        String path = "/v1/hr/home;x=y";
        assertThat(registry.owns("GET", path, null)).isTrue();
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", path, Set.of("APP.HCM:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.personal.home.page", null)).allowed())
                .isFalse();

        String dynamic = "/v1/hr/team/time/00000000-0000-0000-0000-000000000000;x=y/decision";
        assertThat(registry.owns("POST", dynamic, null)).isTrue();
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "POST", dynamic, Set.of("DATA.HR_TIME:APPROVE"), null,
                "NORMAL", Set.of(), "route.hcm.team.time-decision.action", null)).allowed())
                .isFalse();
    }

    @Test
    void repeatedSlashAndDotSegmentsAreClaimedButNeverAuthorized() {
        for (String path : Set.of(
                "/v1/hr//home", "/v1/hr/./home", "/v1/hr/team/../home")) {
            assertThat(registry.owns("GET", path, null)).isTrue();
            assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                    "GET", path, Set.of("APP.HCM:VIEW"), null,
                    "NORMAL", Set.of(), "route.hcm.personal.home.page", null)).allowed())
                    .isFalse();
        }
    }

    @Test
    void exactTeamBindingRequiresTheRegisteredCapability() {
        HcmV3PepRegistry.RequestEvidence allowed = new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hr/team/time", Set.of("DATA.HR_TIME:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.team.time.page", null);
        assertThat(registry.authorize(allowed).allowed()).isTrue();

        HcmV3PepRegistry.RequestEvidence missing = new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hr/team/time", Set.of("APP.HCM:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.team.time.page", null);
        assertThat(registry.authorize(missing).allowed()).isFalse();
    }

    @Test
    void assignmentProposalBindingsExposeExactReadAndStepUpAuthority() {
        HcmV3PepRegistry.Decision read = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET",
                        "/v1/workforce/assignments/00000000-0000-0000-0000-000000000001",
                        Set.of("DATA.WORKFORCE:VIEW"),
                        null,
                        "NORMAL",
                        Set.of(),
                        "route.hcm.operations.assignment-detail.data",
                        null));

        assertThat(read.allowed()).isTrue();
        assertThat(read.authority().capabilityContractKey())
                .isEqualTo("hcm.operations.workforce.read");
        assertThat(read.authority().predicatePolicyKeys())
                .containsExactly("predicate.hcm-workforce-visible-person.v1");
        assertThat(read.authority().targetBindingKinds())
                .containsExactly("TARGET_POPULATION");

        HcmV3PepRegistry.Decision submit = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "POST",
                        "/v1/workforce/assignment-proposals/"
                                + "00000000-0000-0000-0000-000000000002/submit",
                        Set.of("DATA.WORKFORCE:MANAGE"),
                        null,
                        "NORMAL",
                        Set.of(),
                        "route.hcm.operations.assignment-proposal-submit.action",
                        null));

        assertThat(submit.allowed()).isTrue();
        assertThat(submit.authority().capabilityContractKey())
                .isEqualTo("hcm.operations.assignment-proposal.submit");
        assertThat(submit.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(submit.authority().predicatePolicyKeys()).containsExactlyInAnyOrder(
                "predicate.hcm-workforce-visible-person.v1",
                "predicate.people.object-version.v1");
        assertThat(submit.authority().targetBindingKinds()).containsExactlyInAnyOrder(
                "OBJECT", "TARGET_POPULATION");
        HcmV3PepRegistry.StepUpBinding stepUp = submit.authority().stepUpBinding();
        assertThat(stepUp.targetType()).isEqualTo("ASSIGNMENT_PROPOSAL");
        assertThat(stepUp.targetIdPathParameter()).isEqualTo("proposalId");
        assertThat(stepUp.ownerServiceKey()).isEqualTo("people");
        assertThat(stepUp.audience()).isEqualTo("dwp-people-server");
    }

    @Test
    void populationFreezeExposesDedicatedHighRiskAuthorityAndExactStepUpBinding() {
        HcmV3PepRegistry.Decision freeze = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "POST",
                        "/v1/hris/performance/cycles/"
                                + "00000000-0000-0000-0000-000000000003/population-freezes",
                        Set.of("DATA.HR_TALENT:APPROVE"),
                        null,
                        "NORMAL",
                        Set.of(),
                        "route.hcm.operations.performance-population-freeze.action",
                        null));

        assertThat(freeze.allowed()).isTrue();
        assertThat(freeze.authority().capabilityContractKey())
                .isEqualTo("hcm.operations.performance.freeze");
        assertThat(freeze.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(freeze.authority().predicatePolicyKeys()).containsExactlyInAnyOrder(
                "predicate.hcm-domain-target-population.v1",
                "predicate.hcm-performance-cycle-object.v1");
        HcmV3PepRegistry.StepUpBinding stepUp = freeze.authority().stepUpBinding();
        assertThat(stepUp.targetType()).isEqualTo("PERFORMANCE_CYCLE");
        assertThat(stepUp.targetIdPathParameter()).isEqualTo("cycleId");
        assertThat(stepUp.ownerServiceKey()).isEqualTo("people");
        assertThat(stepUp.audience()).isEqualTo("dwp-people-server");
    }

    @Test
    void compensationPlanningUsesExactV39ReadAndIndependentApprovalBindings() {
        HcmV3PepRegistry.Decision read = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hris/performance/compensation-planning/cycles",
                        Set.of("DATA.HRIS_COMPENSATION_PLAN:VIEW"), null,
                        "NORMAL", Set.of(),
                        "route.hcm.operations.compensation-planning.page", null));

        assertThat(read.allowed()).isTrue();
        assertThat(read.authority().capabilityContractKey())
                .isEqualTo("hcm.compensation.plan.view");
        assertThat(read.authority().predicatePolicyKeys())
                .containsExactly("predicate.hcm-compensation-planning-authority.v1");
        assertThat(read.authority().targetBindingKinds())
                .containsExactly("TARGET_POPULATION");

        HcmV3PepRegistry.Decision approve = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "POST", "/v1/hris/performance/compensation-planning/cycles/"
                        + "00000000-0000-0000-0000-000000000004/approve",
                        Set.of("ACTION.HRIS_COMPENSATION_PLAN_APPROVE:APPROVE"), null,
                        "NORMAL", Set.of(),
                        "route.hcm.operations.compensation-plan-approve.action", null));

        assertThat(approve.allowed()).isTrue();
        assertThat(approve.authority().profileKey()).isEqualTo("independent-approver");
        assertThat(approve.authority().capabilityContractKey())
                .isEqualTo("hcm.compensation.plan.approve");
        assertThat(approve.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(approve.authority().stepUpBinding().targetType())
                .isEqualTo("COMPENSATION_CYCLE");
        assertThat(approve.authority().stepUpBinding().targetIdPathParameter())
                .isEqualTo("cycleId");
    }

    @Test
    void legacyHrisApplicationPermissionRemainsAnExactHcmEntitlementAlias() {
        HcmV3PepRegistry.RequestEvidence legacy = new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hr/home", Set.of("APP.HRIS:VIEW"), null,
                "NORMAL", Set.of(), "route.hcm.personal.home.page", null);

        assertThat(registry.authorize(legacy).allowed()).isTrue();

        HcmV3PepRegistry.RequestEvidence wrongAction = new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hr/home", Set.of("APP.HRIS:MANAGE"), null,
                "NORMAL", Set.of(), "route.hcm.personal.home.page", null);
        assertThat(registry.authorize(wrongAction).allowed()).isFalse();
    }

    @Test
    void declaredProviderSupportProfilesCarryClosedProjectionsButNotRuntimeReadiness() {
        List<SupportBinding> bindings = List.of(
                new SupportBinding(
                        "route.hcm.operations.assignments.page",
                        "/v1/workforce/people",
                        "view=assignments",
                        "hcm.support.assignment-list.v1",
                        "HcmSupportAssignmentListV1"),
                new SupportBinding(
                        "route.hcm.operations.overview.page",
                        "/v1/workforce/operations/overview",
                        null,
                        "hcm.support.operations-overview.v1",
                        "HcmSupportOperationsOverviewV1"),
                new SupportBinding(
                        "route.hcm.operations.people.page",
                        "/v1/workforce/people",
                        null,
                        "hcm.support.workforce-list.v1",
                        "HcmSupportWorkforceListV1"),
                new SupportBinding(
                        "route.hcm.operations.people.page",
                        "/v1/workforce/organization/chart",
                        null,
                        "hcm.support.org-summary.v1",
                        "HcmSupportOrgSummaryV1"));

        bindings.forEach(binding -> {
            HcmV3PepRegistry.RequestEvidence evidence =
                    new HcmV3PepRegistry.RequestEvidence(
                            "GET",
                            binding.path(),
                            Set.of(),
                            null,
                            "PROVIDER_SUPPORT",
                            Set.of("WORKFORCE_READ"),
                            binding.route(),
                            binding.query());

            HcmV3PepRegistry.Decision decision = registry.authorize(evidence);

            assertThat(decision.allowed()).as(binding.route() + " " + binding.path()).isTrue();
            assertThat(decision.authority().profileKey()).isEqualTo("provider-support");
            assertThat(decision.authority().readOnly()).isTrue();
            assertThat(decision.authority().projectionPolicyKey())
                    .isEqualTo(binding.projection());
            assertThat(decision.authority().responseSchemaKey()).isEqualTo(binding.schema());

            assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                            "GET", binding.path(), Set.of(), null,
                            "PROVIDER_SUPPORT", Set.of(), binding.route(), binding.query()))
                    .allowed()).isFalse();
        });
    }

    private record SupportBinding(
            String route,
            String path,
            String query,
            String projection,
            String schema) {
    }
}
