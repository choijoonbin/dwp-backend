package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class HcmV3PepRegistryTest {

    private final HcmV3PepRegistry registry =
            new HcmV3PepRegistry(new ObjectMapper().findAndRegisterModules());

    @Test
    void hrm009UsesTrustedScopeToSelectProfilesAndKeepsTheSharedActionHigh() {
        HcmV3PepRegistry.Decision selfRead = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hris/workbench/self-service/requests",
                        Set.of("APP.HCM:VIEW", "DATA.HRIS_SELF_REQUEST:VIEW"),
                        null, "NORMAL", Set.of(),
                        "route.hcm.personal.requests.data", null,
                        "EMPLOYEE_SELF_SERVICE", "SELF"));
        assertThat(selfRead.allowed()).isTrue();
        assertThat(selfRead.authority().profileKey()).isEqualTo("self-request-reader");
        assertThat(selfRead.authority().projectionPolicyKey()).isEqualTo(
                "route.hcm.personal.requests.data.self-request-reader.projection.v1");
        assertThat(selfRead.authority().responseSchemaKey())
                .isEqualTo("Hrm009SelfRequestsProjectionV1");

        HcmV3PepRegistry.Decision operationsRead = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hris/workbench/operations/certificates/documents",
                        Set.of("APP.HCM:VIEW", "DATA.HRIS_WORKFORCE:VIEW"),
                        null, "NORMAL", Set.of(),
                        "route.hcm.operations.certificates-documents.data", null,
                        "HR_OPERATIONS", "ORG_UNIT"));
        assertThat(operationsRead.allowed()).isTrue();
        assertThat(operationsRead.authority().profileKey())
                .isEqualTo("people-operations-reader");
        assertThat(operationsRead.authority().projectionPolicyKey()).isEqualTo(
                "route.hcm.operations.certificates-documents.data."
                        + "people-operations-reader.projection.v1");
        assertThat(operationsRead.authority().responseSchemaKey())
                .isEqualTo("Hrm009CertificatesDocumentsProjectionV1");
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hris/workbench/operations/certificates/documents",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_WORKFORCE:VIEW"),
                null, "NORMAL", Set.of(),
                "route.hcm.operations.certificates-documents.data", null,
                "HR_OPERATIONS", "TARGET_POPULATION")).denialCode())
                .isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "GET", "/v1/hris/workbench/operations/certificates/documents",
                Set.of("APP.HCM:VIEW", "DATA.HRIS_WORKFORCE:VIEW"),
                null, "NORMAL", Set.of(),
                "route.hcm.operations.certificates-documents.data", null,
                "CLIENT_FORGED", "ORG_UNIT")).denialCode())
                .isEqualTo("TRUSTED_PURPOSE_REQUIRED");

        Set<String> dualPermissions = Set.of(
                "APP.HCM:VIEW",
                "ACTION.HRIS_CERTIFICATE:CREATE",
                "ACTION.HRIS_CERTIFICATE:ISSUE");
        HcmV3PepRegistry.Decision selfAction = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "POST", "/v1/hris/certificates", dualPermissions,
                        null, "NORMAL", Set.of(),
                        "route.hcm.operations.certificate-issue.action", null,
                        null, "SELF"));
        HcmV3PepRegistry.Decision operationsAction = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "POST", "/v1/hris/certificates", dualPermissions,
                        null, "NORMAL", Set.of(),
                        "route.hcm.operations.certificate-issue.action", null,
                        null, "TARGET_POPULATION"));
        assertThat(selfAction.allowed()).isTrue();
        assertThat(selfAction.authority().profileKey())
                .isEqualTo("certificate-self-requester");
        assertThat(operationsAction.allowed()).isTrue();
        assertThat(operationsAction.authority().profileKey())
                .isEqualTo("certificate-operations-issuer");
        for (HcmV3PepRegistry.Decision decision : List.of(selfAction, operationsAction)) {
            assertThat(decision.authority().activationPolicy())
                    .isEqualTo("STEPUP-MGMT-HIGH-V1");
            assertThat(decision.authority().highRisk()).isTrue();
            assertThat(decision.authority().stepUpBinding().targetType()).isEqualTo("PERSON");
            assertThat(decision.authority().stepUpBinding().targetIdPathParameter()).isNull();
            assertThat(decision.authority().stepUpBinding().targetIdBodyFields())
                    .containsExactly("personPublicId");
        }
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                "POST", "/v1/hris/certificates", dualPermissions,
                null, "NORMAL", Set.of(),
                "route.hcm.operations.certificate-issue.action", null,
                "TEAM_MANAGEMENT", "TEAM_MANAGEMENT")).allowed()).isFalse();
    }

    @Test
    void hrm011RequiresExactPurposePopulationResponsibilityAndRelationshipStepUp() {
        String resourceSetKey = "RS_HRIS_CONTRACT_ISSUERS_LE_100";
        HcmV3PepRegistry.Decision read = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hris/workbench/operations/contracts/compensation/basis",
                        Set.of("APP.HCM:VIEW", "DATA.HRIS_WORKFORCE:VIEW"),
                        null, "NORMAL", Set.of(),
                        "route.hcm.operations.contracts-compensation-basis.data", null,
                        "HR_OPERATIONS", "ORG_UNIT"));
        assertThat(read.allowed()).isTrue();
        assertThat(read.authority().profileKey()).isEqualTo("people-operations-reader");
        assertThat(read.authority().responseSchemaKey())
                .isEqualTo("Hrm011ContractsCompensationBasisProjectionV2");
        assertThat(read.authority().projectionPolicyKey()).isEqualTo(
                "route.hcm.operations.contracts-compensation-basis.data."
                        + "people-operations-reader.projection.v1");

        var actionEvidence = new HcmV3PepRegistry.RequestEvidence(
                "POST", "/v1/hris/employment-contracts",
                Set.of("APP.HCM:VIEW", "ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE"),
                "HRIS_DUTY_CONTRACT_ISSUER@" + resourceSetKey,
                "NORMAL", Set.of(),
                "route.hcm.operations.employment-contract-issue.action", null,
                "HR_LIFECYCLE", "ORG_UNIT");
        HcmV3PepRegistry.Decision action = registry.authorize(actionEvidence);
        assertThat(action.allowed()).isTrue();
        assertThat(action.authority().profileKey()).isEqualTo("employment-contract-issuer");
        assertThat(action.authority().activationPolicy()).isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(action.authority().stepUpBinding().targetType())
                .isEqualTo("WORK_RELATIONSHIP");
        assertThat(action.authority().stepUpBinding().targetIdBodyFields())
                .containsExactly("relationshipPublicId");

        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                actionEvidence.method(), actionEvidence.path(), actionEvidence.permissions(),
                null, actionEvidence.activeAccessMode(), actionEvidence.supportScopes(),
                actionEvidence.trustedRouteContractKey(), actionEvidence.rawQuery(),
                actionEvidence.trustedPurpose(), actionEvidence.trustedPopulationKind()))
                .denialCode()).isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                actionEvidence.method(), actionEvidence.path(), actionEvidence.permissions(),
                actionEvidence.resourceRoles(), actionEvidence.activeAccessMode(),
                actionEvidence.supportScopes(), actionEvidence.trustedRouteContractKey(),
                actionEvidence.rawQuery(), "HR_OPERATIONS",
                actionEvidence.trustedPopulationKind())).denialCode())
                .isEqualTo("TRUSTED_PURPOSE_REQUIRED");

        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                actionEvidence.method(), actionEvidence.path(), actionEvidence.permissions(),
                "HRIS_DUTY_CONTRACT_ISSUER@RS_HRIS_CONTRACT_ISSUERS_LE_200",
                actionEvidence.activeAccessMode(), actionEvidence.supportScopes(),
                actionEvidence.trustedRouteContractKey(), actionEvidence.rawQuery(),
                actionEvidence.trustedPurpose(), actionEvidence.trustedPopulationKind())).allowed())
                .isTrue();
        assertThat(registry.authorize(new HcmV3PepRegistry.RequestEvidence(
                actionEvidence.method(), actionEvidence.path(), actionEvidence.permissions(),
                "HRIS_DUTY_CONTRACT_ISSUER@WORKFORCE_TARGET_POPULATION",
                actionEvidence.activeAccessMode(), actionEvidence.supportScopes(),
                actionEvidence.trustedRouteContractKey(), actionEvidence.rawQuery(),
                actionEvidence.trustedPurpose(), actionEvidence.trustedPopulationKind())).denialCode())
                .isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
    }

    @Test
    void per008KeepsReadCreateSubmitAndReleaseAuthoritiesNonTransitive() {
        String assignmentId = "00000000-0000-0000-0000-000000000008";
        String requestId = "00000000-0000-0000-0000-000000000009";
        String exactOptionsQuery = "cycleVersionId=00000000-0000-0000-0000-000000000010"
                + "&subjectWorkerPublicId=00000000-0000-0000-0000-000000000011"
                + "&subjectAssignmentPublicId=00000000-0000-0000-0000-000000000012";

        HcmV3PepRegistry.Decision teamPage = registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/workbench/team-performance",
                "route.hcm.team.performance.page", null,
                "DATA.HRIS_TEAM_WORKBENCH:VIEW", "TEAM_MANAGEMENT",
                "TARGET_POPULATION"));
        assertThat(teamPage.allowed()).isTrue();
        assertThat(teamPage.authority().routeKind()).isEqualTo("PAGE");
        assertThat(teamPage.authority().profileKey()).isEqualTo("team-performance-reader");
        assertThat(teamPage.authority().projectionPolicyKey())
                .isEqualTo("per008.iaq-038.fields.v2");

        HcmV3PepRegistry.Decision teamData = registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/workbench/team-performance",
                "route.hcm.team.performance.data", null,
                "DATA.HRIS_TEAM_WORKBENCH:VIEW", "TEAM_MANAGEMENT",
                "TARGET_POPULATION"));
        assertThat(teamData.allowed()).isTrue();
        assertThat(teamData.authority().routeKind()).isEqualTo("DATA");

        for (String route : List.of(
                "route.hcm.personal.performance-feedback.page",
                "route.hcm.personal.feedback-assignments-mine.data")) {
            HcmV3PepRegistry.Decision inbox = registry.authorize(per008Evidence(
                    "GET", "/v1/hris/performance/feedback-assignments/mine",
                    route, null, "DATA.HR_TALENT:VIEW",
                    "PERFORMANCE_FEEDBACK", "SELF"));
            assertThat(inbox.allowed()).as(route).isTrue();
            assertThat(inbox.authority().profileKey()).isEqualTo("self-performance-reader");
            assertThat(inbox.authority().responseSchemaKey())
                    .isEqualTo("Per008AssignedFeedbackListV1");
        }

        HcmV3PepRegistry.Decision managerOptions = registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/feedback-request-options",
                "route.hcm.performance.feedback-request-options.data", exactOptionsQuery,
                "DATA.HRIS_PERFORMANCE_REVIEW:UPDATE", "PERFORMANCE_FEEDBACK",
                "TARGET_POPULATION"));
        assertThat(managerOptions.allowed()).isTrue();
        assertThat(managerOptions.authority().profileKey())
                .isEqualTo("manager-feedback-requester");

        HcmV3PepRegistry.Decision selfOptions = registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/feedback-request-options",
                "route.hcm.performance.feedback-request-options.data", exactOptionsQuery,
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:CREATE", "PERFORMANCE_FEEDBACK",
                "SELF"));
        assertThat(selfOptions.allowed()).isTrue();
        assertThat(selfOptions.authority().profileKey()).isEqualTo("self-feedback-requester");

        HcmV3PepRegistry.Decision create = registry.authorize(per008Evidence(
                "POST", "/v1/hris/performance/feedback-requests",
                "route.hcm.performance.feedback-request-create.action", null,
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:CREATE", "PERFORMANCE_FEEDBACK",
                "SELF"));
        assertThat(create.allowed()).isTrue();
        assertThat(create.authority().capabilityContractKey())
                .isEqualTo("per.feedback.request.create");

        HcmV3PepRegistry.Decision detail = registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/feedback-assignments/" + assignmentId,
                "route.hcm.personal.feedback-assignment-detail.data", null,
                "DATA.HR_TALENT:VIEW", "PERFORMANCE_FEEDBACK", "TARGET_POPULATION"));
        assertThat(detail.allowed()).isTrue();
        assertThat(detail.authority().profileKey()).isEqualTo("assigned-feedback-reader");
        assertThat(detail.authority().stepUpBinding()).isNull();

        HcmV3PepRegistry.Decision submit = registry.authorize(per008Evidence(
                "POST", "/v1/hris/performance/feedback-assignments/" + assignmentId
                        + "/responses",
                "route.hcm.personal.feedback-response-submit.action", null,
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:UPDATE", "PERFORMANCE_FEEDBACK",
                "TARGET_POPULATION"));
        assertThat(submit.allowed()).isTrue();
        assertThat(submit.authority().profileKey()).isEqualTo("assigned-feedback-provider");
        assertThat(submit.authority().capabilityContractKey())
                .isEqualTo("per.feedback.assigned.submit");
        assertThat(submit.authority().stepUpBinding()).isNull();

        for (ReleaseProfile release : List.of(
                new ReleaseProfile("SELF", "self-feedback-release-reader"),
                new ReleaseProfile("TARGET_POPULATION", "manager-feedback-release-reader"),
                new ReleaseProfile("NAMED_POPULATION", "operator-feedback-release-reader"))) {
            HcmV3PepRegistry.Decision decision = registry.authorize(per008Evidence(
                    "GET", "/v1/hris/performance/feedback-requests/" + requestId + "/release",
                    "route.hcm.performance.feedback-release.data", null,
                    "DATA.HRIS_PERFORMANCE_FEEDBACK:VIEW", "PERFORMANCE_FEEDBACK",
                    release.population()));
            assertThat(decision.allowed()).as(release.profile()).isTrue();
            assertThat(decision.authority().profileKey()).isEqualTo(release.profile());
            assertThat(decision.authority().responseSchemaKey())
                    .isEqualTo("Per008FeedbackReleaseV1");
        }

        assertThat(registry.authorize(per008Evidence(
                "POST", "/v1/hris/performance/feedback-assignments/" + assignmentId
                        + "/responses",
                "route.hcm.personal.feedback-response-submit.action", null,
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:CREATE", "PERFORMANCE_FEEDBACK",
                "TARGET_POPULATION")).denialCode())
                .isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
        assertThat(registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/feedback-request-options",
                "route.hcm.performance.feedback-request-options.data", null,
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:CREATE", "PERFORMANCE_FEEDBACK",
                "SELF")).denialCode()).isEqualTo("UNKNOWN_METHOD_PATH_BINDING");
        assertThat(registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/workbench/team-performance",
                "route.hcm.team.performance.data", null,
                "DATA.HRIS_TEAM_WORKBENCH:VIEW", "PERFORMANCE_FEEDBACK",
                "TARGET_POPULATION")).denialCode()).isEqualTo("TRUSTED_PURPOSE_REQUIRED");
        assertThat(registry.authorize(per008Evidence(
                "GET", "/v1/hris/performance/feedback-requests/" + requestId + "/release/extra",
                "route.hcm.performance.feedback-release.data", null,
                "DATA.HRIS_PERFORMANCE_FEEDBACK:VIEW", "PERFORMANCE_FEEDBACK",
                "SELF")).denialCode()).isEqualTo("UNKNOWN_METHOD_PATH_BINDING");
    }

    @Test
    void v59ReleasePredicateAddsOnlyTheSealedSelfBinding() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/hcm-people-pep-v59.generated.json")) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);
            JsonNode predicate = null;
            for (JsonNode candidate : root.path("predicatePolicies")) {
                if ("predicate.hcm-performance-feedback-release.v1".equals(
                        candidate.path("predicatePolicyKey").asText())) {
                    predicate = candidate;
                    break;
                }
            }
            assertThat(predicate).isNotNull();
            List<String> targets = new java.util.ArrayList<>();
            predicate.path("targetBindingKinds").forEach(value -> targets.add(value.asText()));
            assertThat(targets)
                    .containsExactly("SELF", "OBJECT", "TARGET_POPULATION");
        }
    }

    @Test
    void loadsTheExactV59PeopleClosureAndRetainsTheEarlierBindings() throws IOException {
        assertThat(registry.bindingContracts()).hasSize(160);
        assertThat(registry.bindingContracts().stream()
                .map(HcmV3PepRegistry.BindingContract::routeContractKey)
                .distinct()).hasSize(133);
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
                        "/v1/hris/employee-change-requests/{requestPublicId}/apply"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.team.people-changes.data")
                        && value.servicePath().equals(
                        "/v1/hris/workbench/team/people-changes"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.operations.people-appointments.data")
                        && value.servicePath().equals(
                        "/v1/hris/workbench/operations/appointments"))
                .anyMatch(value -> value.routeContractKey()
                        .equals("route.hcm.configuration.people-employment.data")
                        && value.servicePath().equals(
                        "/v1/hris/workbench/configuration/people-employment"))
                .filteredOn(value -> value.servicePath()
                        .equals("/v1/hris/organizations/graph"))
                .extracting(HcmV3PepRegistry.BindingContract::routeContractKey)
                .containsExactlyInAnyOrder(
                        "route.hcm.operations.organization-graph.data",
                        "route.hcm.operations.organization-positions.page");
        Set<String> runtimeBindings = registry.bindingContracts().stream()
                .map(value -> bindingKey(
                        value.routeContractKey(), value.routeKind(),
                        value.method(), value.servicePath()))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> v42Bindings = fixtureBindings(
                "product-authorization/hcm-people-pep-v42.generated.json");
        assertThat(v42Bindings).hasSize(124);
        assertThat(runtimeBindings).containsAll(v42Bindings);
        assertThat(registry.highRiskBindingContracts())
                .hasSize(17)
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
                        "route.hcm.operations.assignment-event-publish.action",
                        "route.hcm.operations.certificate-issue.action",
                        "route.hcm.operations.employment-contract-issue.action",
                        "route.hcm.operations.workforce-ingestion.action");
        assertThat(HcmScopeSelectionValidator.ownerPredicateClosure())
                .hasSize(22)
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
                        "predicate.hcm-organization-graph-authority.v1",
                        "predicate.hcm-performance-cycle-object.v1",
                        "predicate.hcm-performance-cycle-publish-sod.v1",
                        "predicate.hcm-performance-review-assignment.v1",
                        "predicate.hcm-performance-feedback-request.v1",
                        "predicate.hcm-performance-feedback-assignment.v1",
                        "predicate.hcm-performance-feedback-release.v1",
                        "predicate.hcm-workforce-ingestion-scope.v1",
                        "predicate.hcm-workforce-visible-person.v1",
                        "predicate.people.object-version.v1",
                        "predicate.self-person.v1",
                        "predicate.team-target-population.v1");
    }

    @Test
    void organizationPageAndDataShareOnlyTheWirePathNotTheirTrustedRouteIdentity() {
        List<OrganizationRoute> routes = List.of(
                new OrganizationRoute(
                        "route.hcm.operations.organization-positions.page", "PAGE"),
                new OrganizationRoute(
                        "route.hcm.operations.organization-graph.data", "DATA"));

        routes.forEach(route -> {
            HcmV3PepRegistry.Decision decision = registry.authorize(
                    organizationEvidence(route.route(), "/v1/hris/organizations/graph",
                            "HR_OPERATIONS", "TARGET_POPULATION"));

            assertThat(decision.allowed()).as(route.route()).isTrue();
            assertThat(decision.authority().routeContractKey()).isEqualTo(route.route());
            assertThat(decision.authority().routeKind()).isEqualTo(route.kind());
            assertThat(decision.authority().profileKey()).isEqualTo("organization-viewer");
            assertThat(decision.authority().readOnly()).isTrue();
            assertThat(decision.authority().capabilityContractKey())
                    .isEqualTo("hcm.operations.organization.view");
            assertThat(decision.authority().predicatePolicyKeys())
                    .containsExactly("predicate.hcm-organization-graph-authority.v1");
            assertThat(decision.authority().targetBindingKinds())
                    .containsExactly("TARGET_POPULATION");
        });

        assertThat(registry.authorize(organizationEvidence(
                "route.hcm.operations.home.page", "/v1/hris/organizations/graph",
                "HR_OPERATIONS", "TARGET_POPULATION")).denialCode())
                .isEqualTo("UNKNOWN_METHOD_PATH_BINDING");
        for (String path : List.of(
                "/v1/hris/organizations/graph/",
                "/v1/hris/organizations/%67raph",
                "/v1/%68ris/organizations/graph")) {
            assertThat(registry.owns("GET", path, null)).as(path).isTrue();
            assertThat(registry.authorize(organizationEvidence(
                    "route.hcm.operations.organization-graph.data", path,
                    "HR_OPERATIONS", "TARGET_POPULATION")).denialCode())
                    .as(path).isEqualTo("UNKNOWN_METHOD_PATH_BINDING");
        }
        assertThat(registry.authorize(organizationEvidence(
                "route.hcm.operations.organization-graph.data",
                "/v1/hris/organizations/graph", "HR_ADMIN", "TARGET_POPULATION"))
                .denialCode()).isEqualTo("TRUSTED_PURPOSE_REQUIRED");
        assertThat(registry.authorize(organizationEvidence(
                "route.hcm.operations.organization-graph.data",
                "/v1/hris/organizations/graph", "HR_OPERATIONS", "SELF"))
                .denialCode()).isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
        assertThat(registry.authorize(organizationEvidence(
                "route.hcm.operations.organization-graph.data",
                "/v1/hris/organizations/graph", "HR_OPERATIONS", "TARGET_POPULATION",
                "HRIS_DUTY_PAYROLL_VIEWER@ORGANIZATION_TARGET_POPULATION"))
                .denialCode()).isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
        assertThat(registry.authorize(organizationEvidence(
                "route.hcm.operations.organization-graph.data",
                "/v1/hris/organizations/graph", "HR_OPERATIONS", "TARGET_POPULATION",
                "HRIS_DUTY_ORGANIZATION_VIEWER@RS_HCM_OTHER_ORGANIZATION"))
                .denialCode()).isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
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

    private HcmV3PepRegistry.RequestEvidence per008Evidence(
            String method,
            String path,
            String route,
            String query,
            String permission,
            String purpose,
            String population) {
        return new HcmV3PepRegistry.RequestEvidence(
                method, path, Set.of("APP.HCM:VIEW", permission), null,
                "NORMAL", Set.of(), route, query, purpose, population);
    }

    private HcmV3PepRegistry.RequestEvidence organizationEvidence(
            String route,
            String path,
            String purpose,
            String populationKind) {
        return organizationEvidence(
                route, path, purpose, populationKind,
                "HRIS_DUTY_ORGANIZATION_VIEWER@ORGANIZATION_TARGET_POPULATION");
    }

    private HcmV3PepRegistry.RequestEvidence organizationEvidence(
            String route,
            String path,
            String purpose,
            String populationKind,
            String resourceRole) {
        return new HcmV3PepRegistry.RequestEvidence(
                "GET", path,
                Set.of("APP.HCM:VIEW", "DATA.HRIS_ORGANIZATION:VIEW"),
                resourceRole,
                "NORMAL", Set.of(), route, null, purpose, populationKind);
    }

    private Set<String> fixtureBindings(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            JsonNode root = new ObjectMapper().readTree(input);
            Set<String> result = new LinkedHashSet<>();
            root.path("routes").forEach(route -> route.path("servicePepBindings")
                    .forEach(binding -> result.add(bindingKey(
                            route.path("routeContractKey").asText(),
                            route.path("routeKind").asText(),
                            binding.path("method").asText(),
                            binding.path("path").asText()))));
            return Set.copyOf(result);
        }
    }

    private String bindingKey(String route, String kind, String method, String path) {
        return String.join("|", route, kind, method, path);
    }

    private record OrganizationRoute(String route, String kind) {
    }

    private record SupportBinding(
            String route,
            String path,
            String query,
            String projection,
            String schema) {
    }

    private record ReleaseProfile(String population, String profile) {
    }
}
