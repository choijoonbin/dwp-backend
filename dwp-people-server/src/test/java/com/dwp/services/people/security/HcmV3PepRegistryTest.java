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
    void loadsTheExactV3PeopleClosure() {
        assertThat(registry.bindingContracts()).hasSize(75);
        assertThat(registry.bindingContracts().stream()
                .map(HcmV3PepRegistry.BindingContract::routeContractKey)
                .distinct()).hasSize(48);
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
                        .equals("/v1/workforce/organization/candidates"));
        assertThat(registry.highRiskBindingContracts())
                .hasSize(7)
                .extracting(HcmV3PepRegistry.BindingContract::routeContractKey)
                .containsExactlyInAnyOrder(
                        "route.hcm.management.org-publish.action",
                        "route.hcm.management.controlled-export-create.action",
                        "route.hcm.management.controlled-export-retry.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.management.integration-execute.action",
                        "route.hcm.management.integration-execute.action");
        assertThat(HcmScopeSelectionValidator.ownerPredicateClosure())
                .hasSize(11)
                .containsOnlyKeys(
                        "predicate.directory-visible-person.v1",
                        "predicate.hcm-configuration-scope.v1",
                        "predicate.hcm-domain-target-population.v1",
                        "predicate.hcm-export-population.v1",
                        "predicate.hcm-integration-nonsecret-update.v1",
                        "predicate.hcm-org-approval-sod.v1",
                        "predicate.hcm-org-publish-sod.v1",
                        "predicate.hcm-workforce-visible-person.v1",
                        "predicate.people.object-version.v1",
                        "predicate.self-person.v1",
                        "predicate.team-target-population.v1");
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
