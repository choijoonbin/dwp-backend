package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductRouteCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeneratedProductRouteCatalog catalog = catalog(3);

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
        GeneratedProductRouteCatalog latest = catalog(14);

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

    private void assertInvalid(ObjectNode bundle) throws Exception {
        byte[] value = objectMapper.writeValueAsBytes(bundle);
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper, new ByteArrayResource(value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority endpoint");
    }
}
