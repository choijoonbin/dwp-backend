package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedProductRouteCatalogTest extends GeneratedProductRouteCatalogTestSupport {

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

    private void assertRoute(
            GeneratedProductRouteCatalog catalog,
            String method,
            String path,
            String routeKey,
            boolean stateChanging,
            String query) {
        GeneratedProductRouteCatalog.Match match = catalog.match(method, path, query);
        assertThat(match.status()).as(method + " " + path)
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
            assertThat(route.routeContractKey()).isEqualTo(routeKey);
            assertThat(route.stateChanging()).isEqualTo(stateChanging);
        });
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

    private void assertCatalogInvalid(ObjectNode value, String message) throws Exception {
        assertThatThrownBy(() -> new GeneratedProductRouteCatalog(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(value))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
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

    private ObjectNode route(ObjectNode bundle, String routeKey) {
        for (JsonNode route : bundle.withArray("routes")) {
            if (routeKey.equals(route.path("routeContractKey").asText())) {
                return (ObjectNode) route;
            }
        }
        throw new IllegalArgumentException("Missing route: " + routeKey);
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
    void v33SeparatesLegacyPersonalAndSystemManagementViewsForBothOwnerApis() {
        GeneratedProductRouteCatalog latest = catalog(33);
        for (List<String> binding : List.of(
                List.of(
                        "/api/auth/hris/product-access/snapshot",
                        "route.hcm.personal.product-access-snapshot.data"),
                List.of(
                        "/api/platform/v1/hris/configuration/projection",
                        "route.hcm.personal.configuration-projection.data"))) {
            String path = binding.get(0);
            var legacy = latest.match("GET", path, null);
            var system = latest.match("GET", path, "view=system");

            assertThat(legacy.status()).as(path)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(legacy.uniqueRoute().routeContractKey()).as(path).isEqualTo(binding.get(1));
            assertThat(system.status()).as(path)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(system.uniqueRoute().routeContractKey()).as(path)
                    .isEqualTo("route.hcm.management.system.page");
            assertThat(system.uniqueRoute().surfaceKey()).as(path).isEqualTo("hcm.management");
            assertThat(latest.match("GET", path, "view=unknown").status()).as(path)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
            assertThat(latest.match("GET", path, "view=system&view=system").status()).as(path)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        }
    }

    @Test
    void v33ClaimsOnlyTheHrisFamilyWithinTheSharedAuthService() {
        GeneratedProductRouteCatalog latest = catalog(33);

        assertThat(latest.match("GET", "/api/auth/me").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
        assertThat(latest.match("GET", "/api/auth/hris/not-registered").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void requiredQueryConstraintAcceptsExactlyOneNonBlankDynamicValue() throws Exception {
        ObjectNode document = bundle(3);
        ObjectNode route = null;
        ObjectNode binding = null;
        for (JsonNode candidate : document.withArray("routes")) {
            for (JsonNode candidateBinding : candidate.path("gatewayApiBindings")) {
                if ("POST".equals(candidateBinding.path("method").asText())
                        && "/api/approvals/v1/admin/form-categories".equals(
                        candidateBinding.path("path").asText())) {
                    route = (ObjectNode) candidate;
                    binding = (ObjectNode) candidateBinding;
                }
            }
        }
        assertThat(route).isNotNull();
        assertThat(binding).isNotNull();
        binding.putObject("queryParameterConstraints")
                .putObject("effectiveOn")
                .put("kind", "REQUIRED");

        GeneratedProductRouteCatalog required = new GeneratedProductRouteCatalog(
                objectMapper,
                new ByteArrayResource(objectMapper.writeValueAsBytes(document)));

        assertThat(required.match("POST", "/api/approvals/v1/admin/form-categories",
                "effectiveOn=2026-09-29").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(required.match("POST", "/api/approvals/v1/admin/form-categories", null)
                .status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(required.match("POST", "/api/approvals/v1/admin/form-categories",
                "effectiveOn=").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(required.match("POST", "/api/approvals/v1/admin/form-categories",
                "effectiveOn=2026-09-29&effectiveOn=2026-09-30").status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
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
    void keepsExactSharedRuntimeCodeSetsOutsideTheHcmProductBoundary() {
        GeneratedProductRouteCatalog latest = catalog(32);

        for (String codeSet : List.of(
                "PLATFORM.API_HISTORY.WINDOW",
                "PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER",
                "PLATFORM.API_HISTORY.HTTP_METHOD_FILTER",
                "PLATFORM.API_HISTORY.OUTCOME_FILTER",
                "PLATFORM.PREFERENCE.COLOR_MODE",
                "PLATFORM.PREFERENCE.DENSITY",
                "PLATFORM.PREFERENCE.TIME_ZONE",
                "PLATFORM.PREFERENCE.DATE_FORMAT",
                "PLATFORM.PREFERENCE.TIME_FORMAT",
                "PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
                "PLATFORM.PREFERENCE.NUMBER_FORMAT",
                "PLATFORM.AUDIT.WINDOW",
                "PLATFORM.AUDIT.CATEGORY_FILTER",
                "PLATFORM.AUDIT.SEVERITY_FILTER",
                "PLATFORM.AUDIT.OUTCOME_FILTER",
                "PLATFORM.EVENT_ENVELOPE.DOMAIN",
                "PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
                "PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT")) {
            assertThat(latest.match(
                            "GET", "/api/platform/v1/catalog/code-sets/" + codeSet, "locale=ko")
                    .status())
                    .as(codeSet)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED);
            assertThat(latest.match(
                            "POST", "/api/platform/v1/catalog/code-sets/" + codeSet, "locale=ko")
                    .status())
                    .as(codeSet + " write")
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        }

        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PEOPLE.HRIS_SOURCE_TYPE",
                        "locale=ko&contextScopeKey=scope%3Ahcm%2Fdefault")
                .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.UNKNOWN",
                        "locale=ko")
                .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.PREFERENCE.UNKNOWN",
                        "locale=ko")
                .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.AUDIT.UNKNOWN",
                        "locale=ko")
                .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.EVENT_ENVELOPE.UNKNOWN",
                        "locale=ko")
                .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.SYS_AUDIT_EXPORT_JOBS.UNKNOWN",
                        "locale=ko")
                .status())
                .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
    }

    @Test
    void v32GovernsOnlyTheExactCommunicationsManagementCodeSets() {
        GeneratedProductRouteCatalog latest = catalog(32);

        for (String codeSet : List.of(
                "PLATFORM.COMMUNICATION.CATEGORY",
                "PLATFORM.COMMUNICATION.CONTENT_TYPE")) {
            var match = latest.match(
                    "GET", "/api/platform/v1/catalog/code-sets/" + codeSet, "locale=ko");
            assertThat(match.status()).as(codeSet)
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute().routeContractKey()).as(codeSet)
                    .isEqualTo("route.communications.management.code-sets.data");
            assertThat(match.uniqueRoute().productKey()).as(codeSet)
                    .isEqualTo("communications");
        }

        assertThat(latest.match(
                        "GET",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.COMMUNICATION.UNKNOWN",
                        "locale=ko")
                .status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
        assertThat(latest.match(
                        "POST",
                        "/api/platform/v1/catalog/code-sets/PLATFORM.COMMUNICATION.CATEGORY",
                        null)
                .status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
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
    void v21ClosesTheFrozenHumanAndExactRoomInventoryAndPublishesEveryPage()
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
        Set<String> openApi = platformV21OpenApiWorkplaceBindings();
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

    @Test
    void latestClosesTheCurrentHumanAndExactRoomInventoryAndPublishesEveryPage()
            throws IOException {
        GeneratedProductRouteCatalog latest = catalog(31);
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
        assertThat(openApi).hasSize(321).containsAll(device);
        Set<String> human = new HashSet<>(openApi);
        human.removeAll(device);
        assertThat(human).hasSize(311);
        assertThat(generated.stream()
                .filter(binding -> binding.contains(" /api/platform/v1/workplace/")
                        || binding.contains(" /api/platform/v1/admin/workplace/"))
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(human);
        assertThat(generated).hasSize(328).doesNotContainAnyElementsOf(device);
        assertThat(generated.stream()
                .filter(binding -> binding.contains(" /api/platform/v1/rooms/")
                        || binding.contains(" /api/platform/v1/admin/rooms/"))
                .collect(java.util.stream.Collectors.toSet()))
                .hasSize(14)
                .contains(
                        "GET /api/platform/v1/admin/rooms/policy",
                        "GET /api/platform/v1/rooms/bookings",
                        "PUT /api/platform/v1/admin/rooms/resources/{resourceId}");
        assertThat(generated.stream()
                .filter(binding -> binding.contains(" /api/platform/v2/home"))
                .collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "GET /api/platform/v2/home",
                        "POST /api/platform/v2/home/shadow-receipts",
                        "POST /api/platform/v2/home/widget-actions:execute");

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

}
