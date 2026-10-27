package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductRouteTopologyTest extends GeneratedProductRouteCatalogTestSupport {

    private static final Set<String> GENERIC_SESSION_STEP_UP_ROUTES = Set.of(
            "route.hcm.configuration.configuration-version.data",
            "route.hcm.configuration.configurations.data",
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.payroll-run.data",
            "route.hcm.operations.time-close-validate.action",
            "route.hcm.operations.time-interpretation-run.action",
            "route.hcm.operations.time-interpretation-trace.data",
            "route.hcm.operations.time-payroll-handoff-create.action",
            "route.hcm.operations.time-payroll-handoff.data",
            "route.hcm.time.timecard-correction.action",
            "route.hcm.time.timecard.data");

    private static final Set<String> V54_GENERIC_SESSION_STEP_UP_ROUTES = Set.of(
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data");
    private static final Set<String> V58_GENERIC_SESSION_STEP_UP_ROUTES = Set.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data");
    private static final Set<String> V59_PERFORMANCE_FEEDBACK_ROUTES = Set.of(
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data",
            "route.hcm.personal.performance-feedback.page",
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action",
            "route.hcm.personal.feedback-assignments-mine.data",
            "route.hcm.personal.feedback-assignment-detail.data",
            "route.hcm.personal.feedback-response-submit.action",
            "route.hcm.performance.feedback-release.data");

    @Test
    void v62IntroducesOnlyTheDedicatedHrm013ApprovalCommandBinding() throws Exception {
        GeneratedProductRouteCatalog previous = catalog(61);
        GeneratedProductRouteCatalog current = catalog(62);
        String path = "/api/approvals/v1/hris/separation-waiver-tasks/task-62/approve";
        String routeKey =
                "route.approvals.work.hris-separation-waiver-task-approve.action";

        assertThat(previous.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .doesNotContain(routeKey);
        GeneratedProductRouteCatalog.Match match = current.match("POST", path);
        assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.routes()).hasSize(1);
        assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
            assertThat(route.routeContractKey()).isEqualTo(routeKey);
            assertThat(route.productKey()).isEqualTo("approvals");
            assertThat(route.surfaceKey()).isEqualTo("approvals.work");
            assertThat(route.routeKind()).isEqualTo("ACTION");
            assertThat(route.stateChanging()).isTrue();
            assertThat(route.highRiskStepUp()).isTrue();
            assertThat(route.commandBoundStepUp()).isTrue();
            assertThat(route.genericSessionStepUp()).isFalse();
        });
        assertThat(current.match("GET", path).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(current.match("POST", path + "/extra").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void v59IntroducesOnlyTheExactPer008TopologiesAndPreservesEquivalentReads()
            throws Exception {
        GeneratedProductRouteCatalog previous = catalog(58);
        GeneratedProductRouteCatalog current = catalog(59);

        assertThat(previous.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .doesNotContainAnyElementsOf(V59_PERFORMANCE_FEEDBACK_ROUTES);
        assertThat(current.routesForTesting().stream()
                .filter(route -> V59_PERFORMANCE_FEEDBACK_ROUTES.contains(
                        route.routeContractKey())))
                .hasSize(9);

        record EquivalentRead(String path, String page, String data, String equivalence) { }
        for (EquivalentRead read : List.of(
                new EquivalentRead(
                        "/api/people/v1/hris/performance/workbench/team-performance",
                        "route.hcm.team.performance.page",
                        "route.hcm.team.performance.data",
                        "wire-authority.hcm.team.performance.v1"),
                new EquivalentRead(
                        "/api/people/v1/hris/performance/feedback-assignments/mine",
                        "route.hcm.personal.performance-feedback.page",
                        "route.hcm.personal.feedback-assignments-mine.data",
                        "wire-authority.hcm.personal.performance-feedback-inbox.v1"))) {
            GeneratedProductRouteCatalog.Match match = current.match("GET", read.path());
            assertThat(match.status()).as(read.path())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.routes()).hasSize(2)
                    .allSatisfy(route -> assertThat(route.authorizationEquivalenceKey())
                            .isEqualTo(read.equivalence()))
                    .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                    .containsExactlyInAnyOrder(read.page(), read.data());
            assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(read.data());
                assertThat(route.routeKind()).isEqualTo("DATA");
            });
        }

        assertRoute(current, "GET",
                "/api/people/v1/hris/performance/feedback-request-options",
                "route.hcm.performance.feedback-request-options.data", false,
                "cycleVersionId=cycle-1&subjectWorkerPublicId=worker-1"
                        + "&subjectAssignmentPublicId=assignment-1");
        assertRoute(current, "POST",
                "/api/people/v1/hris/performance/feedback-requests",
                "route.hcm.performance.feedback-request-create.action", true, null);
        assertRoute(current, "GET",
                "/api/people/v1/hris/performance/feedback-assignments/assignment-1",
                "route.hcm.personal.feedback-assignment-detail.data", false, null);
        assertRoute(current, "POST",
                "/api/people/v1/hris/performance/feedback-assignments/assignment-1/responses",
                "route.hcm.personal.feedback-response-submit.action", true, null);
        assertRoute(current, "GET",
                "/api/people/v1/hris/performance/feedback-requests/request-1/release",
                "route.hcm.performance.feedback-release.data", false, null);
        assertThat(current.match("GET",
                "/api/people/v1/hris/performance/feedback-request-options").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(current.match("PUT",
                "/api/people/v1/hris/performance/feedback-requests").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(current.match("GET",
                "/api/people/v1/hris/performance/feedback-assignments/assignment-1/extra")
                .status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);

        ObjectNode introducedEarly = bundle(58);
        introducedEarly.withArray("routes").add(route(bundle(59),
                "route.hcm.team.performance.page").deepCopy());
        assertCatalogInvalid(introducedEarly, "introduced early");

        ObjectNode widened = bundle(59);
        ((ObjectNode) route(widened,
                "route.hcm.personal.feedback-assignment-detail.data")
                .withArray("gatewayApiBindings").get(0))
                .put("path", "/api/people/v1/hris/performance/feedback-assignments/"
                        + "{feedbackAssignmentId}/{extra}");
        assertCatalogInvalid(widened, "v59 performance-feedback topology");

        ObjectNode missing = bundle(59);
        ArrayNode routes = missing.withArray("routes");
        for (int index = 0; index < routes.size(); index++) {
            if ("route.hcm.performance.feedback-release.data"
                    .equals(routes.get(index).path("routeContractKey").asText())) {
                routes.remove(index);
                break;
            }
        }
        assertCatalogInvalid(missing, "v59 performance-feedback topology closed set");

        ObjectNode duplicated = bundle(59);
        duplicated.withArray("routes").add(route(duplicated,
                "route.hcm.performance.feedback-release.data").deepCopy());
        assertCatalogInvalid(duplicated, "performance-feedback route is duplicated");
    }

    @Test
    void v58IntroducesOnlyTheExactPay004EquivalentReadPairAndSealsBothTopologies()
            throws Exception {
        GeneratedProductRouteCatalog previous = catalog(57);
        GeneratedProductRouteCatalog current = catalog(58);
        String path = "/api/payroll/v1/workbench/pay-retirement";

        assertThat(previous.routesForTesting().stream()
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .doesNotContainAnyElementsOf(V58_GENERIC_SESSION_STEP_UP_ROUTES);
        GeneratedProductRouteCatalog.Match match = current.match("GET", path);
        assertThat(match.status()).isEqualTo(
                GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.routes()).hasSize(2)
                .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                .containsExactlyInAnyOrderElementsOf(V58_GENERIC_SESSION_STEP_UP_ROUTES);
        assertThat(match.routes()).allSatisfy(route -> {
            assertThat(route.authorizationEquivalenceKey())
                    .isEqualTo("wire-authority.hcm.operations.pay-retirement.v1");
            assertThat(route.highRiskStepUp()).isTrue();
            assertThat(route.commandBoundStepUp()).isFalse();
            assertThat(route.genericSessionStepUp()).isTrue();
            assertThat(route.stateChanging()).isFalse();
        });
        assertThat(match.uniqueRoute()).isNotNull()
                .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                .isEqualTo("route.hcm.operations.pay-retirement.data");
        assertThat(current.match("POST", path).status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);

        ObjectNode widened = bundle(58);
        ((ObjectNode) route(widened, "route.hcm.operations.pay-retirement.data")
                .withArray("gatewayApiBindings").get(0))
                .put("path", path + "/{caseId}");
        assertCatalogInvalid(widened, "generic session step-up topology");

        ObjectNode missing = bundle(58);
        ArrayNode routes = missing.withArray("routes");
        for (int index = 0; index < routes.size(); index++) {
            if ("route.hcm.operations.pay-retirement.page"
                    .equals(routes.get(index).path("routeContractKey").asText())) {
                routes.remove(index);
                break;
            }
        }
        assertCatalogInvalid(missing, "closed set is incomplete");
    }

    @Test
    void v47ResolvesOnlyTheGeneratorValidatedFirstReadSources() {
        record CanonicalRead(String path, String query, String canonicalPage, String source) { }
        List<CanonicalRead> reads = List.of(
                new CanonicalRead(
                        "/api/time/v1/hris/work-plans", "effectiveOn=2026-10-20",
                        "route.hcm.operations.time-work-plans.page",
                        "route.hcm.operations.work-plans-list.data"),
                new CanonicalRead(
                        "/api/people/v1/hris/performance/cycles", null,
                        "route.hcm.operations.performance-goal-cycles.page",
                        "route.hcm.operations.performance-cycles-list.data"),
                new CanonicalRead(
                        "/api/people/v1/hr/team/absence", null,
                        "route.hcm.team.leave.page", "route.hcm.team.absence.page"),
                new CanonicalRead(
                        "/api/people/v1/hr/operations/ABSENCE", null,
                        "route.hcm.operations.time-leave-operations.page",
                        "route.hcm.operations.absence.page"),
                new CanonicalRead(
                        "/api/people/v1/workforce/operations/overview", null,
                        "route.hcm.operations.home.page",
                        "route.hcm.operations.overview.page"));

        for (GeneratedProductRouteCatalog candidate : List.of(catalog(47), catalog(55))) {
            for (CanonicalRead read : reads) {
                GeneratedProductRouteCatalog.Match match =
                        candidate.match("GET", read.path(), read.query());
                assertThat(match.status()).as(read.path())
                        .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
                assertThat(match.routes()).as(read.path()).hasSize(2)
                        .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                        .containsExactlyInAnyOrder(read.canonicalPage(), read.source());
                assertThat(match.uniqueRoute()).as(read.path()).isNotNull()
                        .satisfies(route -> {
                            assertThat(route.routeContractKey()).isEqualTo(read.source());
                            assertThat(route.stateChanging()).isFalse();
                        });
            }
        }
    }

    @Test
    void v54AddsOnlyTheTwoPay003GenericSessionReadsToTheSealedSet() {
        GeneratedProductRouteCatalog v53 = catalog(53);
        GeneratedProductRouteCatalog v54 = catalog(54);

        assertThat(v53.routesForTesting().stream()
                .filter(GeneratedProductRouteCatalog.Route::genericSessionStepUp)
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsExactlyInAnyOrderElementsOf(GENERIC_SESSION_STEP_UP_ROUTES);
        assertThat(v54.routesForTesting().stream()
                .filter(GeneratedProductRouteCatalog.Route::genericSessionStepUp)
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(
                                        GENERIC_SESSION_STEP_UP_ROUTES.stream(),
                                        V54_GENERIC_SESSION_STEP_UP_ROUTES.stream())
                                .collect(java.util.stream.Collectors.toSet()));
        assertThat(V54_GENERIC_SESSION_STEP_UP_ROUTES).allSatisfy(routeKey ->
                assertThat(v54.routesForTesting().stream()
                        .filter(route -> routeKey.equals(route.routeContractKey()))
                        .findFirst().orElseThrow())
                        .satisfies(route -> {
                            assertThat(route.highRiskStepUp()).isTrue();
                            assertThat(route.commandBoundStepUp()).isFalse();
                            assertThat(route.genericSessionStepUp()).isTrue();
                        }));
    }

    @Test
    void v52CanonicalizesHrm009SameWireReadsAndSealsTheSingleHighAction() {
        GeneratedProductRouteCatalog v52 = catalog(52);
        record Pair(String path, String dataRoute, String equivalenceKey) { }
        for (Pair pair : List.of(
                new Pair(
                        "/api/people/v1/hris/workbench/self-service/requests",
                        "route.hcm.personal.requests.data",
                        "wire-authority.hcm.personal.requests.v1"),
                new Pair(
                        "/api/people/v1/hris/workbench/operations/certificates/documents",
                        "route.hcm.operations.certificates-documents.data",
                        "wire-authority.hcm.operations.certificates-documents.v1"))) {
            GeneratedProductRouteCatalog.Match match = v52.match("GET", pair.path());
            assertThat(match.status()).as(pair.path())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.routes()).as(pair.path()).hasSize(2)
                    .allSatisfy(route -> assertThat(route.authorizationEquivalenceKey())
                            .isEqualTo(pair.equivalenceKey()));
            assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(pair.dataRoute());
                assertThat(route.routeKind()).isEqualTo("DATA");
                assertThat(route.stateChanging()).isFalse();
            });
        }

        GeneratedProductRouteCatalog.Route action = v52.match(
                "POST", "/api/people/v1/hris/certificates").uniqueRoute();
        assertThat(action).isNotNull().satisfies(route -> {
            assertThat(route.routeContractKey())
                    .isEqualTo("route.hcm.operations.certificate-issue.action");
            assertThat(route.routeKind()).isEqualTo("ACTION");
            assertThat(route.stateChanging()).isTrue();
            assertThat(route.highRiskStepUp()).isTrue();
            assertThat(route.commandBoundStepUp()).isTrue();
            assertThat(route.genericSessionStepUp()).isFalse();
        });
    }

    @Test
    void v51RoutesHrmWirePairsButNeverPublishesAuthorizationOnlySysPages() {
        GeneratedProductRouteCatalog latest = catalog(51);
        assertThat(latest.match("GET",
                "/api/people/v1/hris/workbench/team/people-changes").uniqueRoute()
                .routeContractKey()).isEqualTo("route.hcm.team.people-changes.data");
        assertThat(latest.match("GET",
                "/api/people/v1/hris/workbench/operations/appointments").uniqueRoute()
                .routeContractKey()).isEqualTo(
                        "route.hcm.operations.people-appointments.data");
        assertThat(latest.match("GET",
                "/api/people/v1/hris/workbench/configuration/people-employment")
                .uniqueRoute().routeContractKey()).isEqualTo(
                        "route.hcm.configuration.people-employment.data");
        assertThat(latest.routesForTesting())
                .noneMatch(route -> Set.of(
                        "route.hcm.configuration.workflow.page",
                        "route.hcm.team.delegation.page")
                        .contains(route.routeContractKey()));
    }

    @Test
    void v49PublishesOnlyTheExactTwelveGenericSessionStepUpRoutes() throws Exception {
        GeneratedProductRouteCatalog latest = catalog(49);
        assertThat(latest.routesForTesting().stream()
                .filter(GeneratedProductRouteCatalog.Route::genericSessionStepUp)
                .map(GeneratedProductRouteCatalog.Route::routeContractKey))
                .containsExactlyInAnyOrderElementsOf(GENERIC_SESSION_STEP_UP_ROUTES);
        assertThat(latest.routesForTesting().stream()
                .filter(GeneratedProductRouteCatalog.Route::genericSessionStepUp))
                .allSatisfy(route -> {
                    assertThat(route.highRiskStepUp()).isTrue();
                    assertThat(route.commandBoundStepUp()).isFalse();
                });
        for (String fresh : List.of(
                "route.hcm.operations.payroll-run.action",
                "route.hcm.operations.payroll-run-calculate.action",
                "route.hcm.operations.time-close.action",
                "route.hcm.operations.time-reopen.action")) {
            assertThat(latest.routesForTesting().stream()
                    .filter(route -> fresh.equals(route.routeContractKey()))
                    .findFirst().orElseThrow().genericSessionStepUp()).as(fresh).isFalse();
        }

        ObjectNode drifted = bundle(49);
        ((ObjectNode) route(drifted, "route.hcm.configuration.configurations.data")
                .withArray("servicePepBindings").get(0))
                .put("path", "/v1/admin/hris/configurations-drift");
        assertCatalogInvalid(drifted, "generic session step-up topology");

        ObjectNode thirteenth = bundle(49);
        route(thirteenth, "route.hcm.operations.payroll-run-calculate.action")
                .remove("stepUpCommandBindings");
        assertCatalogInvalid(thirteenth, "generic session step-up route is not sealed");

        ObjectNode missing = bundle(49);
        ArrayNode routes = missing.withArray("routes");
        for (int index = 0; index < routes.size(); index++) {
            if ("route.hcm.operations.time-payroll-handoff.data"
                    .equals(routes.get(index).path("routeContractKey").asText())) {
                routes.remove(index);
                break;
            }
        }
        assertCatalogInvalid(missing, "closed set is incomplete");
    }

    @Test
    void v49LoadsTheExactInternalAuthorityEndpointWithoutPublishingIt() throws Exception {
        GeneratedProductRouteCatalog latest = catalog(49);

        assertThat(latest.authorityEndpointsForTesting()).hasSize(2);
        assertThat(latest.authorityEndpoint(
                "POST", "/api/auth/product-surface-step-up-challenges"))
                .extracting(GeneratedProductRouteCatalog.AuthorityEndpoint::endpointKey)
                .isEqualTo("product-surface-step-up-challenge.issue");
        assertThat(latest.authorityEndpoint(
                "POST", "/internal/auth/v1/hris-navigation-policy-decisions/evaluate"))
                .isNull();

        ObjectNode mixedTopology = bundle(49);
        ObjectNode internal = (ObjectNode) mixedTopology.withArray("authorityEndpoints").get(1);
        internal.put("publicPath", "/api/auth/hris-navigation-policy-decisions/evaluate");
        assertInvalid(mixedTopology);
    }

    @Test
    void v49TraversesDirectAnyAndAllActivatedCapabilitiesAndSeparatesFreshBindings()
            throws Exception {
        GeneratedProductRouteCatalog latest = catalog(49);
        assertRoute(latest, "GET", "/api/payroll/v1/runs/run-1",
                "route.hcm.operations.payroll-run.data", true);
        assertThat(latest.match("GET", "/api/payroll/v1/runs/run-1")
                .uniqueRoute().commandBoundStepUp()).isFalse();
        assertRoute(latest, "POST", "/api/time/v1/close-periods/period-1/validations",
                "route.hcm.operations.time-close-validate.action", true);
        assertThat(latest.match("POST", "/api/time/v1/close-periods/period-1/validations")
                .uniqueRoute().commandBoundStepUp()).isFalse();
        for (List<String> binding : List.of(
                List.of("POST", "/api/payroll/v1/runs",
                        "route.hcm.operations.payroll-run.action"),
                List.of("POST", "/api/payroll/v1/runs/run-1/attempts",
                        "route.hcm.operations.payroll-run-calculate.action"),
                List.of("POST", "/api/time/v1/close-periods/period-1/close",
                        "route.hcm.operations.time-close.action"),
                List.of("POST", "/api/time/v1/close-periods/period-1/reopen",
                        "route.hcm.operations.time-reopen.action"))) {
            var route = latest.match(binding.get(0), binding.get(1)).uniqueRoute();
            assertThat(route.routeContractKey()).isEqualTo(binding.get(2));
            assertThat(route.highRiskStepUp()).isTrue();
            assertThat(route.commandBoundStepUp()).isTrue();
        }

        ObjectNode allExpression = bundle(49);
        ObjectNode payrollRun = route(allExpression, "route.hcm.operations.payroll-run.data");
        ((ObjectNode) payrollRun.withArray("accessProfiles").get(0)
                .path("requiredAccess")).put("mode", "ALL");
        GeneratedProductRouteCatalog allCatalog = new GeneratedProductRouteCatalog(
                objectMapper, new ByteArrayResource(objectMapper.writeValueAsBytes(allExpression)));
        assertThat(allCatalog.match("GET", "/api/payroll/v1/runs/run-1")
                .uniqueRoute().highRiskStepUp()).isTrue();

        ObjectNode unknownReference = bundle(49);
        ObjectNode unknownRoute = route(
                unknownReference, "route.hcm.operations.payroll-run.data");
        ((ObjectNode) unknownRoute.withArray("accessProfiles").get(0)
                .path("requiredAccess")).withArray("capabilityContractKeys")
                .set(0, objectMapper.getNodeFactory().textNode("missing.capability"));
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(unknownReference))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown capability");
    }

    @Test
    void v41GovernsExactPayrollRunPepsAndOnlyElevatesApprovalAndFinalize() {
        GeneratedProductRouteCatalog latest = catalog(41);
        for (List<String> binding : List.of(
                List.of("POST", "/api/payroll/v1/runs/"
                                + "00000000-0000-4000-8000-000000000001/attempts",
                        "route.hcm.operations.payroll-run-calculate.action", "ACTION", "false"),
                List.of("GET", "/api/payroll/v1/runs/"
                                + "00000000-0000-4000-8000-000000000001",
                        "route.hcm.operations.payroll-run.data", "DATA", "false"),
                List.of("POST", "/api/payroll/v1/runs/"
                                + "00000000-0000-4000-8000-000000000001/approvals",
                        "route.hcm.operations.payroll-run-approve.action", "ACTION", "true"),
                List.of("POST", "/api/payroll/v1/runs/"
                                + "00000000-0000-4000-8000-000000000001/finalize",
                        "route.hcm.operations.payroll-run-finalize.action", "ACTION", "true"),
                List.of("GET", "/api/payroll/v1/command-receipts/"
                                + "00000000-0000-4000-8000-000000000002",
                        "route.hcm.operations.payroll-command-receipt.data", "DATA", "false"))) {
            var match = latest.match(binding.get(0), binding.get(1));
            assertThat(match.status()).as(binding.toString())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute().routeContractKey()).isEqualTo(binding.get(2));
            assertThat(match.uniqueRoute().routeKind()).isEqualTo(binding.get(3));
            assertThat(match.uniqueRoute().highRiskStepUp())
                    .isEqualTo(Boolean.parseBoolean(binding.get(4)));
            assertThat(catalog(40).match(binding.get(0), binding.get(1)).uniqueRoute())
                    .as(binding.toString()).isNull();
        }

        assertThat(latest.match("DELETE", "/api/payroll/v1/runs/"
                        + "00000000-0000-4000-8000-000000000001").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    private void assertCatalogInvalid(ObjectNode value, String message) throws Exception {
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(value))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private ObjectNode route(ObjectNode bundle, String routeKey) {
        for (JsonNode route : bundle.withArray("routes")) {
            if (routeKey.equals(route.path("routeContractKey").asText())) {
                return (ObjectNode) route;
            }
        }
        throw new IllegalArgumentException("Missing route: " + routeKey);
    }

}
