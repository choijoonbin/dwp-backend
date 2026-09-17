package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductRouteCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeneratedProductRouteCatalog catalog = catalog(3);

    @Test
    void v21GovernsEveryAiRuntimeControlBindingWithExactMethods() {
        GeneratedProductRouteCatalog latest = catalog(21);
        for (List<String> binding : List.of(
                List.of("GET", "/api/agent/v1/admin/ai-control",
                        "route.dwaion.management.ai-control.page", "DATA"),
                List.of("POST", "/api/agent/v1/admin/ai-control/bootstrap",
                        "route.dwaion.management.ai-control-bootstrap.action", "ACTION"),
                List.of("PUT", "/api/agent/v1/admin/ai-control/policy",
                        "route.dwaion.management.ai-control-update.action", "ACTION"),
                List.of("POST", "/api/agent/v1/admin/ai-control/emergency",
                        "route.dwaion.management.ai-control-emergency.action", "ACTION"))) {
            var match = latest.match(binding.get(0), binding.get(1));
            assertThat(match.status()).as(binding.toString())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute().routeContractKey()).isEqualTo(binding.get(2));
            assertThat(match.uniqueRoute().routeKind()).isEqualTo(binding.get(3));
            assertThat(match.uniqueRoute().stateChanging())
                    .isEqualTo("ACTION".equals(binding.get(3)));
        }
        assertThat(latest.match("PATCH", "/api/agent/v1/admin/ai-control/policy").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(catalog(20).match("GET", "/api/agent/v1/admin/ai-control").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
    }

    @Test
    void officialBundleAuthorityEndpointTopologyIsV1NoneAndV2V3ExactOne() {
        assertThat(catalog(1).authorityEndpointsForTesting()).isEmpty();
        for (int version : List.of(2, 3)) {
            assertThat(catalog(version).authorityEndpointsForTesting())
                    .singleElement()
                    .satisfies(endpoint -> {
                        assertThat(endpoint.endpointKey())
                                .isEqualTo("product-surface-step-up-challenge.issue");
                        assertThat(endpoint.method()).isEqualTo("POST");
                        assertThat(endpoint.publicPath())
                                .isEqualTo("/api/auth/product-surface-step-up-challenges");
                        assertThat(endpoint.expectedDecisionRevisionHeader())
                                .isEqualTo("X-DWP-Expected-Decision-Revision");
                    });
        }
    }

    @Test
    void v6RequesterResponseHasAnExactNewSourceAction() {
        String path = "/api/platform/v1/services/requests/00000000-0000-4000-8000-000000000001/information-response";
        var latest = catalog(6).match("POST", path);
        assertThat(latest.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(latest.uniqueRoute().routeContractKey())
                .isEqualTo("route.services.work.request-information-response.action");
        assertThat(latest.uniqueRoute().productKey()).isEqualTo("services");
        assertThat(catalog(5).match("POST", path).uniqueRoute()).isNull();
        assertThat(catalog(6).match("PUT", path).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void v6MeetingMutationsResolveOnlyToTheirDedicatedActionRoutes() {
        for (List<String> binding : List.of(
                List.of(
                        "/api/meetings/v1/meetings/"
                                + "00000000-0000-4000-8000-000000000001/participants/"
                                + "00000000-0000-4000-8000-000000000002/disconnect",
                        "route.meetings.work.participant-disconnect.action"),
                List.of(
                        "/api/meetings/v1/meetings/"
                                + "00000000-0000-4000-8000-000000000001/"
                                + "intelligence/reports/"
                                + "00000000-0000-4000-8000-000000000003/exports",
                        "route.meetings.work.intelligence-report-export.action"))) {
            String path = binding.get(0);
            var latest = catalog(6).match("POST", path);

            assertThat(latest.status()).as(path)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(latest.uniqueRoute().routeContractKey())
                    .as(path).isEqualTo(binding.get(1));
            assertThat(latest.uniqueRoute().routeKind()).as(path).isEqualTo("ACTION");
            assertThat(latest.uniqueRoute().stateChanging()).as(path).isTrue();
            assertThat(catalog(5).match("POST", path).uniqueRoute()).as(path).isNull();
            assertThat(catalog(6).match("GET", path).status()).as(path)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        }
    }

    @Test
    void v6ClosesEveryDeclaredDwaionUserAndAdministrationMutation() {
        GeneratedProductRouteCatalog latest = catalog(6);
        for (List<String> binding : List.of(
                List.of("POST", "/api/agent/v1/proposals/"
                        + "00000000-0000-4000-8000-000000000001/decisions",
                        "route.dwaion.work.proposal-decision.action"),
                List.of("POST", "/api/agent/v1/routines/"
                        + "00000000-0000-4000-8000-000000000001/archive",
                        "route.dwaion.work.routine-archive.action"),
                List.of("POST", "/api/agent/v1/personal-data/deletions",
                        "route.dwaion.work.personal-deletion-request.action"),
                List.of("POST", "/api/agent/v1/artifacts/"
                        + "00000000-0000-4000-8000-000000000001/exports",
                        "route.dwaion.work.artifact-export.action"),
                List.of("POST", "/api/agent/v1/admin/gates/PRODUCTION_READINESS/decision",
                        "route.dwaion.management.gate-decision.action"),
                List.of("POST", "/api/platform/v1/admin/dwaion/agents/DWP_ASSISTANT/"
                        + "revisions/2/activate",
                        "route.dwaion.management.agent-revision-activate.action"))) {
            var match = latest.match(binding.get(0), binding.get(1));
            assertThat(match.status()).as(binding.toString())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute().routeContractKey()).as(binding.toString())
                    .isEqualTo(binding.get(2));
            assertThat(match.uniqueRoute().stateChanging()).as(binding.toString()).isTrue();
            assertThat(catalog(5).match(binding.get(0), binding.get(1)).uniqueRoute())
                    .as(binding.toString()).isNull();
        }

        long dwaionRoutes = latest.routesForTesting().stream()
                .filter(route -> "dwaion".equals(route.productKey()))
                .count();
        long dwaionActions = latest.routesForTesting().stream()
                .filter(route -> "dwaion".equals(route.productKey()))
                .filter(GeneratedProductRouteCatalog.Route::stateChanging)
                .count();
        assertThat(dwaionRoutes).isEqualTo(95);
        assertThat(dwaionActions).isEqualTo(57);
    }

    @Test
    void declaredAuthorityEndpointUsesAStrictClosedShape() throws Exception {
        ObjectNode unknownField = bundle(3);
        ((ObjectNode) unknownField.withArray("authorityEndpoints").get(0))
                .put("unknownAuthorityField", "must-fail");
        assertInvalid(unknownField);

        ObjectNode empty = bundle(3);
        empty.set("authorityEndpoints", objectMapper.createArrayNode());
        assertInvalid(empty);

        ObjectNode duplicate = bundle(3);
        ArrayNode endpoints = duplicate.withArray("authorityEndpoints");
        endpoints.add(endpoints.get(0).deepCopy());
        assertInvalid(duplicate);
    }

    @Test
    void collapsesOnlyGeneratorValidatedApprovalWireEquivalence() {
        var tasks = catalog.match("GET", "/api/approvals/v1/tasks", null);
        var requests = catalog.match("GET", "/api/approvals/v1/requests", null);

        assertThat(tasks.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(tasks.routes()).hasSize(2);
        assertThat(tasks.uniqueRoute().authorizationEquivalenceKey())
                .isEqualTo("wire-authority.approvals.work.tasks-list.v1");
        assertThat(requests.routes()).hasSize(4);
        assertThat(requests.uniqueRoute()).isNotNull();
    }

    @Test
    void resolvesNonEquivalentWorkflowViewsByGeneratedQueryConstraint() {
        var page = catalog.match(
                "GET", "/api/approvals/v1/admin/workflows", null);
        var reference = catalog.match(
                "GET", "/api/approvals/v1/admin/workflows", "view=reference");
        var duplicate = catalog.match(
                "GET", "/api/approvals/v1/admin/workflows",
                "view=reference&view=reference");

        assertThat(page.uniqueRoute().routeContractKey())
                .isEqualTo("route.approvals.admin.workflows.page");
        assertThat(reference.uniqueRoute().routeContractKey())
                .isEqualTo("route.approvals.admin.forms-workflow-reference.data");
        assertThat(duplicate.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void staticApprovalSearchRouteOutranksTheTaskIdTemplate() {
        GeneratedProductRouteCatalog latest = catalog(15);

        var search = latest.match(
                "GET", "/api/approvals/v1/tasks/search", "contextScopeKey=scope-47");
        var detail = latest.match(
                "GET", "/api/approvals/v1/tasks/task-47", null);

        assertThat(search.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(search.routes()).singleElement().satisfies(route ->
                assertThat(route.routeContractKey())
                        .isEqualTo("route.approvals.work.tasks-search.data"));
        assertThat(search.uniqueRoute()).isNotNull();
        assertThat(detail.uniqueRoute().routeContractKey())
                .isEqualTo("route.approvals.work.task-detail.data");
    }

    @Test
    void appliesFixedParametersBeforeChoosingTheProductOwner() {
        var approval = catalog.match(
                "PUT", "/api/platform/v1/home-preferences/surfaces/approval-home", null);
        var hcm = catalog.match(
                "PUT", "/api/platform/v1/home-preferences/surfaces/hcm-home", null);
        var spoofed = catalog.match(
                "PUT", "/api/platform/v1/home-preferences/surfaces/services-home", null);

        assertThat(approval.uniqueRoute().productKey()).isEqualTo("approvals");
        assertThat(hcm.uniqueRoute().productKey()).isEqualTo("hcm");
        assertThat(spoofed.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void distinguishesUnknownProductNamespaceFromNonProductTraffic() {
        assertThat(catalog.match("GET", "/api/approvals/v1/not-registered", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match("GET", "/api/auth/me", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(catalog.match("DELETE", "/api/approvals/v1/tasks", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void incrementalProductSiblingsPassThroughWithoutRelaxingGovernedCandidates() {
        GeneratedProductRouteCatalog incremental = catalog(4);
        for (var request : List.of(
                List.of("GET", "/api/platform/v1/workplace/bookings"),
                List.of("GET", "/api/spaces/v1/spaces"),
                List.of("GET", "/api/spaces/v1/requests"),
                List.of("POST", "/api/spaces/v1/spaces/company-square/content"))) {
            assertThat(incremental.match(request.get(0), request.get(1), null).status())
                    .as(request.get(0) + " " + request.get(1))
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        }

        assertThat(incremental.match(
                        "GET", "/api/platform/v1/workplace/explore", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(incremental.match(
                        "GET", "/api/spaces/v1/spaces/company-square/content", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(catalog.match(
                        "GET", "/api/approvals/v1/not-registered", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void exposesOnlyTheFourExactLegacyWorkforceAccessBindings() {
        assertThat(catalog.match(
                        "GET", "/api/people/v1/admin/workforce/access-policies", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT);
        assertThat(catalog.match(
                        "GET",
                        "/api/people/v1/admin/workforce/access-policies/organizations",
                        null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT);
        assertThat(catalog.match(
                        "POST", "/api/people/v1/admin/workforce/access-policies", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT);
        assertThat(catalog.match(
                        "PATCH",
                        "/api/people/v1/admin/workforce/access-policies/policy-7/revoke",
                        null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT);
    }

    @Test
    void legacyWorkforceAccessBoundaryRejectsVerbAndPathShapeDrift() {
        for (String method : List.of("PUT", "DELETE")) {
            assertThat(catalog.match(
                            method, "/api/people/v1/admin/workforce/access-policies", null).status())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        }
        assertThat(catalog.match(
                        "GET", "/api/people/v1/admin/workforce/access-policies/", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match(
                        "GET",
                        "/api/people/v1/admin/workforce/access-policies/organizations/",
                        null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match(
                        "POST", "/api/people/v1/admin/workforce/access-policies/", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        for (String path : List.of(
                "/api/people/v1/admin/workforce/access-policies//revoke",
                "/api/people/v1/admin/workforce/access-policies/policy-7/revoke/",
                "/api/people/v1/admin/workforce/access-policies/policy-7/revoke/extra")) {
            assertThat(catalog.match("PATCH", path, null).status())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        }
        assertThat(catalog.match(
                        "GET",
                        "/api/people/v1/admin/workforce/access-policies/policy-7/revoke",
                        null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match(
                        "GET", "/api/people/v1/admin/workforce/not-registered", null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(catalog.match(
                        "GET", "/api/people/v1/admin/workforce/access-policies", "%").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void exportsTheCanonicalIssuerWithoutTreatingItAsDomainPepTraffic() {
        var endpoint = catalog.authorityEndpoint(
                "POST", "/api/auth/product-surface-step-up-challenges");

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.requiresAuthentication()).isTrue();
        assertThat(endpoint.requiresCsrf()).isTrue();
        assertThat(endpoint.expectedDecisionRevisionHeader())
                .isEqualTo("X-DWP-Expected-Decision-Revision");
        assertThat(catalog.match(
                "POST", endpoint.publicPath(), null).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
    }

    @Test
    void approvalV3Preserves49BindingsAndCollapsesTo43WireAuthorities() {
        Set<String> wireBindings = new HashSet<>();
        Set<String> authorities = new HashSet<>();
        int bindings = 0;
        for (GeneratedProductRouteCatalog.Route route : catalog.routesForTesting()) {
            if (!"approvals".equals(route.productKey())) continue;
            bindings++;
            wireBindings.add(route.method() + " " + route.publicPath());
            String equivalence = route.authorizationEquivalenceKey();
            authorities.add(equivalence == null
                    ? route.method() + " " + route.publicPath() + " " + route.queryConstraints()
                    : route.method() + " " + route.publicPath() + " " + equivalence);
        }
        assertThat(bindings).isEqualTo(49);
        assertThat(wireBindings).hasSize(43);
        // Two non-equivalent workflow wire resources are intentionally split by `view`.
        assertThat(authorities).hasSize(45);
    }

    @Test
    void criticalHcmExportCommandsRemainStepUpEligibleAtTheGateway() {
        assertThat(catalog.match(
                        "POST", "/api/people/v1/workforce/exports", null)
                .uniqueRoute())
                .satisfies(route -> {
                    assertThat(route.routeContractKey()).isEqualTo(
                            "route.hcm.management.controlled-export-create.action");
                    assertThat(route.stateChanging()).isTrue();
                    assertThat(route.highRiskStepUp()).isTrue();
                });
        assertThat(catalog.match(
                        "PATCH",
                        "/api/people/v1/workforce/exports/"
                                + "11111111-1111-1111-1111-111111111111/retry",
                        null)
                .uniqueRoute().highRiskStepUp()).isTrue();
    }

    @Test
    void v15AppendsOnlyExactApr17Through24ApprovalBindings() throws Exception {
        ObjectNode previous = bundle(14);
        ObjectNode latest = bundle(15);
        Set<String> previousKeys = routeKeys(previous);
        List<JsonNode> appended = new ArrayList<>();
        latest.withArray("routes").forEach(route -> {
            if (!previousKeys.contains(route.path("routeContractKey").asText())
                    && "approvals".equals(
                    route.path("subject").path("productKey").asText())) {
                appended.add(route);
            }
        });

        assertThat(appended).hasSize(43);
        assertThat(appended.stream().filter(route -> "PAGE".equals(
                route.path("routeKind").asText()))).hasSize(5);
        assertThat(appended.stream().filter(route -> "DATA".equals(
                route.path("routeKind").asText()))).hasSize(18);
        assertThat(appended.stream().filter(route -> "ACTION".equals(
                route.path("routeKind").asText()))).hasSize(20);

        int publicBindings = 0;
        int stepUpBindings = 0;
        Set<String> pagePaths = new HashSet<>();
        for (JsonNode route : appended) {
            assertThat(route.path("subject").path("productKey").asText())
                    .isEqualTo("approvals");
            ArrayNode publicApi = (ArrayNode) route.path("gatewayApiBindings");
            ArrayNode servicePep = (ArrayNode) route.path("servicePepBindings");
            assertThat(publicApi).hasSameSizeAs(servicePep);
            publicBindings += publicApi.size();
            if ("PAGE".equals(route.path("routeKind").asText())) {
                pagePaths.add(route.path("uiRoutePattern").asText());
            }
            for (int index = 0; index < publicApi.size(); index++) {
                JsonNode gateway = publicApi.get(index);
                JsonNode service = servicePep.get(index);
                assertThat(gateway.path("bindingKey").asText())
                        .isEqualTo(service.path("bindingKey").asText());
                assertThat(gateway.path("method").asText())
                        .isEqualTo(service.path("method").asText());
                assertThat(gateway.path("path").asText())
                        .isEqualTo("/api/approvals" + service.path("path").asText());
                assertThat(gateway.path("path").asText()).doesNotContain("*");
                assertThat(service.path("serviceKey").asText()).isEqualTo("approval");
            }
            for (JsonNode command : route.path("stepUpCommandBindings")) {
                stepUpBindings++;
                assertThat(command.path("ownerServiceKey").asText()).isEqualTo("approval");
                assertThat(command.path("audience").asText()).isEqualTo("dwp-approval-server");
                assertThat(command.path("expectedObjectVersionSource").asText())
                        .isEqualTo("COMMAND_HEADER");
                assertThat(command.path("expectedObjectVersionName").asText())
                        .isEqualTo("X-DWP-Expected-Object-Version");
                assertThat(command.hasNonNull("targetIdPathParameter")
                        ^ command.path("targetIdBodyFields").isArray()).isTrue();
            }
        }

        assertThat(publicBindings).isEqualTo(89);
        assertThat(stepUpBindings).isEqualTo(28);
        assertThat(pagePaths).containsExactlyInAnyOrder(
                "/approvals/admin/routing",
                "/approvals/admin/integrations",
                "/approvals/admin/audit",
                "/approvals/admin/analytics",
                "/approvals/admin/deployments");
    }

    @Test
    void v15GatewayCatalogSeparatesLowRiskDraftsFromExactHighRiskCommands() {
        GeneratedProductRouteCatalog latest = catalog(15);

        assertRoute(latest, "PUT",
                "/api/approvals/v1/admin/workflows/routing-directory/groups/group-17",
                "route.approvals.admin.routing-directory-update.action", false);
        assertRoute(latest, "POST",
                "/api/approvals/v1/admin/workflows/routing-directory/groups/group-17/publish",
                "route.approvals.admin.routing-directory-publish.action", true);
        assertRoute(latest, "PUT",
                "/api/approvals/v1/admin/policies/automation/rules/policy-17/draft",
                "route.approvals.admin.policy-automation-update.action", false);
        assertRoute(latest, "POST",
                "/api/approvals/v1/admin/policies/automation/rules/policy-17/publish",
                "route.approvals.admin.policy-automation-publish.action", true);
        assertRoute(latest, "POST",
                "/api/approvals/v1/admin/operations/deployments/packages",
                "route.approvals.admin.deployment-package-create.action", true);

        assertThat(latest.match("POST",
                "/api/approvals/v1/admin/forms/templates/template-17/publish").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match("GET",
                "/api/approvals/v1/admin/operations/deployments/not-registered").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match("DELETE",
                "/api/approvals/v1/admin/policies/automation/rules/policy-17/draft").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void v15RoutesEveryWorkplaceConnectorRuntimeOperationToOneExactContract() {
        GeneratedProductRouteCatalog latest = catalog(15);

        assertRoute(latest, "GET",
                "/api/platform/v1/admin/workplace/connectors/operations",
                "route.workplace.management.connector-operations.data", false);
        assertRoute(latest, "GET",
                "/api/platform/v1/admin/workplace/connectors/CALENDAR/operations",
                "route.workplace.management.connector-operation.data", false);
        assertRoute(latest, "POST",
                "/api/platform/v1/admin/workplace/connectors/CALENDAR/replays:preview",
                "route.workplace.management.connector-replay-preview.action", false);
        assertRoute(latest, "POST",
                "/api/platform/v1/admin/workplace/connectors/CALENDAR/replays",
                "route.workplace.management.connector-replay-start.action", false);
        assertRoute(latest, "GET",
                "/api/platform/v1/admin/workplace/connectors/CALENDAR/replays/job-17",
                "route.workplace.management.connector-replay-status.data", false);

        assertThat(latest.match("GET",
                "/api/platform/v1/admin/workplace/connectors/UNKNOWN/operations").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void v15CollapsesFindAndPlannerPagesOntoTheExplicitExploreWireAuthority() {
        var match = catalog(15).match(
                "GET", "/api/platform/v1/workplace/explore");

        assertThat(match.status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.routes()).hasSize(3)
                .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                .containsExactlyInAnyOrder(
                        "route.workplace.work.explore.page",
                        "route.workplace.work.find.page",
                        "route.workplace.work.planner.page");
        assertThat(match.routes())
                .extracting(GeneratedProductRouteCatalog.Route::authorizationEquivalenceKey)
                .containsOnly("wire-authority.workplace.work.explore.v1");
        assertThat(match.uniqueRoute().routeContractKey())
                .isEqualTo("route.workplace.work.explore.page");
    }

    @Test
    void v15KeepsBothReservationsReadModelsUnderOnePageContract() {
        GeneratedProductRouteCatalog latest = catalog(15);

        assertRoute(latest, "GET",
                "/api/platform/v1/workplace/bookings",
                "route.workplace.work.reservations.page", false);
        assertRoute(latest, "GET",
                "/api/platform/v1/rooms/bookings",
                "route.workplace.work.reservations.page", false);
    }

    @Test
    void v18GovernsEveryServiceHistoryLineAdjustmentAndAttachmentScanRoute() {
        GeneratedProductRouteCatalog latest = catalog(18);
        GeneratedProductRouteCatalog previous = catalog(17);
        String order = "00000000-0000-4000-8000-000000000018";
        String line = "00000000-0000-4000-8000-000000000019";
        String adjustment = "00000000-0000-4000-8000-000000000020";
        String attachment = "00000000-0000-4000-8000-000000000021";

        for (List<String> binding : List.of(
                List.of("GET", "/api/platform/v1/workplace/service-orders/" + order
                                + "/events",
                        "route.workplace.work.service-order-events.data"),
                List.of("GET", "/api/platform/v1/workplace/service-orders/" + order
                                + "/messages",
                        "route.workplace.work.service-order-messages.data"),
                List.of("GET", "/api/platform/v1/workplace/service-orders/" + order
                                + "/attachments",
                        "route.workplace.work.service-order-attachments.data"),
                List.of("POST", "/api/platform/v1/workplace/service-orders/" + order
                                + "/lines/" + line + "/cancellation-impact:preview",
                        "route.workplace.work.service-order-line-cancellation-impact.action"),
                List.of("POST", "/api/platform/v1/workplace/service-orders/" + order
                                + "/lines/" + line + ":cancel",
                        "route.workplace.work.service-order-line-cancel.action"),
                List.of("GET", "/api/platform/v1/workplace/service-orders/" + order
                                + "/line-adjustments/" + adjustment,
                        "route.workplace.work.service-order-line-adjustment.data"),
                List.of("GET", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/events",
                        "route.workplace.management.service-fulfillment-events.data"),
                List.of("GET", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/messages",
                        "route.workplace.management.service-fulfillment-messages.data"),
                List.of("GET", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/attachments",
                        "route.workplace.management.service-fulfillment-attachments.data"),
                List.of("GET", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/line-adjustments/" + adjustment,
                        "route.workplace.management.service-fulfillment-line-adjustment.data"),
                List.of("POST", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/line-adjustments/" + adjustment + ":reconcile",
                        "route.workplace.management.service-fulfillment-line-adjustment-reconcile.action"),
                List.of("POST", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/attachments/" + attachment + "/scan-result",
                        "route.workplace.management.service-fulfillment-attachment-scan-result.action"),
                List.of("GET", "/api/platform/v1/admin/workplace/service-orders/" + order
                                + "/attachments/" + attachment + "/scan-status",
                        "route.workplace.management.service-fulfillment-attachment-scan-status.data"))) {
            var match = latest.match(binding.get(0), binding.get(1));
            assertThat(match.status()).as(binding.toString())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute().routeContractKey()).as(binding.toString())
                    .isEqualTo(binding.get(2));
            assertThat(previous.match(binding.get(0), binding.get(1)).uniqueRoute())
                    .as(binding.toString()).isNull();
        }

        assertThat(latest.match("DELETE",
                "/api/platform/v1/workplace/service-orders/" + order + "/events").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(latest.match("GET",
                "/api/platform/v1/admin/workplace/service-orders/" + order
                        + "/attachments/" + attachment + "/scan-result").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(latest.match("POST",
                "/api/platform/v1/admin/workplace/service-orders/" + order
                        + "/attachments/" + attachment + "/scan-status").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
    }

    @Test
    void v21ClosesTheCurrentHumanAndExactRoomInventoryAndPublishesEveryPage()
            throws IOException {
        GeneratedProductRouteCatalog latest = catalog(21);
        Set<String> generated = latest.routesForTesting().stream()
                .filter(route -> "workplace".equals(route.productKey()))
                .filter(route -> route.publicPath().startsWith("/api/platform/"))
                .map(route -> route.method() + " " + route.publicPath())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> device = Set.of(
                "POST /api/platform/v1/device/workplace/devices/{deviceId}/heartbeat",
                "GET /api/platform/v1/device/workplace/devices/{deviceId}/projection",
                "POST /api/platform/v1/device/workplace/devices:register",
                "POST /api/platform/v1/device/workplace/devices/{deviceId}/access-pass:pair",
                "POST /api/platform/v1/workplace/kiosk/devices/{deviceId}:heartbeat",
                "POST /api/platform/v1/workplace/kiosk/devices/{deviceId}:help",
                "GET /api/platform/v1/workplace/kiosk/session",
                "GET /api/platform/v1/workplace/kiosk/visits/{visitId}",
                "POST /api/platform/v1/workplace/kiosk/visits/{visitId}:arrive",
                "POST /api/platform/v1/workplace/kiosk/visits/{visitId}:checkout");
        Set<String> openApi = platformOpenApiWorkplaceBindings();
        assertThat(openApi).hasSize(299).containsAll(device);
        Set<String> human = new HashSet<>(openApi);
        human.removeAll(device);
        assertThat(human).hasSize(289);
        assertThat(generated.stream()
                .filter(binding -> binding.contains(" /api/platform/v1/workplace/")
                        || binding.contains(" /api/platform/v1/admin/workplace/"))
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(human);
        assertThat(generated).hasSize(303).doesNotContainAnyElementsOf(device);
        assertThat(generated.stream()
                .filter(binding -> binding.contains(" /api/platform/v1/rooms/")
                        || binding.contains(" /api/platform/v1/admin/rooms/"))
                .collect(java.util.stream.Collectors.toSet()))
                .hasSize(14)
                .contains(
                        "GET /api/platform/v1/admin/rooms/policy",
                        "GET /api/platform/v1/rooms/bookings",
                        "PUT /api/platform/v1/admin/rooms/resources/{resourceId}");

        assertThat(latest.routesForTesting().stream()
                .filter(route -> "workplace".equals(route.productKey()))
                .filter(route -> "PAGE".equals(route.routeKind()))
                .map(GeneratedProductRouteCatalog.Route::routeContractKey)
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "route.workplace.work.home.page",
                        "route.workplace.work.explore.page",
                        "route.workplace.work.find.page",
                        "route.workplace.work.planner.page",
                        "route.workplace.work.reservations.page",
                        "route.workplace.work.service-orders.page",
                        "route.workplace.work.wayfinding.page",
                        "route.workplace.work.assistant.page",
                        "route.workplace.work.safety.page",
                        "route.workplace.management.service-catalog.page",
                        "route.workplace.management.service-fulfillment.page",
                        "route.workplace.management.devices.page",
                        "route.workplace.management.exceptions.page",
                        "route.workplace.management.service-providers.page",
                        "route.workplace.management.space-planning.page",
                        "route.workplace.management.assistant-governance.page",
                        "route.workplace.management.governance.page",
                        "route.workplace.management.safety.page",
                        "route.workplace.management.visits.page",
                        "route.workplace.management.visit-policies.page",
                        "route.workplace.management.access-zones.page",
                        "route.workplace.management.visit-providers.page",
                        "route.workplace.management.kiosk-devices.page",
                        "route.workplace.management.overview.page",
                        "route.workplace.management.operations.page",
                        "route.workplace.management.locations.page",
                        "route.workplace.management.policy.page",
                        "route.workplace.management.room-operations.page",
                        "route.workplace.management.room-policy.page");

        assertRoute(latest, "GET", "/api/platform/v1/admin/workplace/safety/incidents",
                "route.workplace.management.safety.page", false);
        assertRoute(latest, "GET", "/api/platform/v1/admin/workplace/visits/exceptions",
                "route.workplace.management.visits.page", false);
        assertRoute(latest, "GET", "/api/platform/v1/admin/workplace/visit-policies",
                "route.workplace.management.visit-policies.page", false);
        assertRoute(latest, "GET", "/api/platform/v1/admin/workplace/access-zones",
                "route.workplace.management.access-zones.page", false);
        assertRoute(latest, "GET", "/api/platform/v1/admin/workplace/provider-bindings",
                "route.workplace.management.visit-providers.page", false);
        assertRoute(latest, "GET", "/api/platform/v1/admin/workplace/kiosk-devices",
                "route.workplace.management.kiosk-devices.page", false);
    }

    private GeneratedProductRouteCatalog catalog(int version) {
        return new GeneratedProductRouteCatalog(
                objectMapper,
                new FileSystemResource("../contracts/product-authorization/"
                        + "product-surfaces-v1.bundle-v" + version + ".json"));
    }

    private ObjectNode bundle(int version) throws IOException {
        try (var input = new FileSystemResource(
                "../contracts/product-authorization/product-surfaces-v1.bundle-v"
                        + version + ".json").getInputStream()) {
            return (ObjectNode) objectMapper.readTree(input);
        }
    }

    private Set<String> platformOpenApiWorkplaceBindings() throws IOException {
        Path path = Path.of("contracts/openapi/platform.json");
        if (!Files.exists(path)) path = Path.of("../contracts/openapi/platform.json");
        JsonNode document = objectMapper.readTree(Files.readAllBytes(path));
        Set<String> methods = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
        Set<String> result = new HashSet<>();
        document.path("paths").properties().forEach(pathEntry -> {
            String servicePath = pathEntry.getKey();
            if (!(servicePath.startsWith("/v1/workplace/")
                    || servicePath.startsWith("/v1/admin/workplace/")
                    || servicePath.startsWith("/v1/device/workplace/"))) {
                return;
            }
            pathEntry.getValue().properties().forEach(operation -> {
                String method = operation.getKey().toUpperCase(java.util.Locale.ROOT);
                if (methods.contains(method)) {
                    result.add(method + " /api/platform" + servicePath);
                }
            });
        });
        return Set.copyOf(result);
    }

    private Set<String> routeKeys(ObjectNode bundle) {
        Set<String> result = new HashSet<>();
        bundle.withArray("routes").forEach(route ->
                result.add(route.path("routeContractKey").asText()));
        return result;
    }

    private void assertRoute(
            GeneratedProductRouteCatalog source,
            String method,
            String path,
            String routeKey,
            boolean highRisk) {
        var match = source.match(method, path);
        assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.uniqueRoute()).satisfies(route -> {
            assertThat(route.routeContractKey()).isEqualTo(routeKey);
            assertThat(route.highRiskStepUp()).isEqualTo(highRisk);
        });
    }

    private void assertInvalid(ObjectNode bundle) throws Exception {
        byte[] value = objectMapper.writeValueAsBytes(bundle);
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper, new ByteArrayResource(value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority endpoint");
    }
}
