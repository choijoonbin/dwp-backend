package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceDecisionContextFilterTest {

    private static final String REVISION = "psr-" + "a".repeat(64);
    private static final OffsetDateTime REVALIDATE_AT =
            OffsetDateTime.parse("2036-08-24T00:00:00Z");
    private static final String HCM_DERIVED_SCOPE = "hcm-scope-" + "c".repeat(40);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
            objectMapper,
            new ClassPathResource("product-authorization/product-surfaces-v1.generated.json"));

    @Test
    void latestCatalogRegistersExactDwaionV5ReadBindingsAsSideEffectFree() {
        Map<String, String> expected = Map.of(
                "/api/agent/v1/runs", "route.dwaion.work.runs.data",
                "/api/agent/v1/runs/40000000-0000-4000-8000-000000000017",
                "route.dwaion.work.run-detail.data",
                "/api/agent/v1/activity/events", "route.dwaion.work.activity-events.data",
                "/api/agent/v1/activity/events/40000000-0000-4000-8000-000000000017",
                "route.dwaion.work.activity-event.data",
                "/api/agent/v1/activity/executions/summary",
                "route.dwaion.work.activity-summary.data");

        expected.forEach((path, routeKey) -> {
            GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
            assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(routeKey);
                assertThat(route.productKey()).isEqualTo("dwaion");
                assertThat(route.surfaceKey()).isEqualTo("dwaion.work");
                assertThat(route.routeKind()).isEqualTo("DATA");
                assertThat(route.stateChanging()).isFalse();
            });
        });
    }

    @Test
    void latestCatalogEnforcesExactDwaionV6StateChangeBindingsAndRevision() {
        record Case(String method, String path, String routeKey) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/agent/v1/ask/stream",
                        "route.dwaion.work.ask-stream.action"),
                new Case("PATCH",
                        "/api/agent/v1/conversations/40000000-0000-4000-8000-000000000017",
                        "route.dwaion.work.conversation-rename.action"),
                new Case("DELETE",
                        "/api/agent/v1/conversations/40000000-0000-4000-8000-000000000017",
                        "route.dwaion.work.conversation-delete.action"));

        for (Case requestCase : cases) {
            GeneratedProductRouteCatalog.Match match =
                    catalog.match(requestCase.method(), requestCase.path());
            assertThat(match.status())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(requestCase.routeKey());
                assertThat(route.productKey()).isEqualTo("dwaion");
                assertThat(route.surfaceKey()).isEqualTo("dwaion.work");
                assertThat(route.routeKind()).isEqualTo("ACTION");
                assertThat(route.stateChanging()).isTrue();
            });

            ProductSurfaceContextAggregationService authority = authority(allowedDwaion());
            ProductSurfaceDecisionContextFilter filter = filter(authority);
            MockServerHttpRequest.BaseBuilder<?> builder =
                    MockServerHttpRequest.method(
                                    HttpMethod.valueOf(requestCase.method()), requestCase.path())
                            .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                            .header(VerifiedIdentityFilter.USER_HEADER, "41")
                            .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                            .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                    REVISION);
            MockServerWebExchange exchange = exchange(builder);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .isEqualTo(requestCase.routeKey());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                    .isEqualTo(REVISION);

            MockServerWebExchange stale = exchange(
                    MockServerHttpRequest.method(
                                    HttpMethod.valueOf(requestCase.method()), requestCase.path())
                            .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                            .header(VerifiedIdentityFilter.USER_HEADER, "41")
                            .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                            .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                    "psr-" + "b".repeat(64)));
            AtomicReference<Boolean> staleForwarded = new AtomicReference<>(false);

            filter.filter(stale, ignored -> {
                staleForwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(staleForwarded.get()).isFalse();
            assertThat(stale.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(body(stale)).contains("DECISION_REVISION_CONFLICT");
        }
    }

    @Test
    void enforcedHomePreferenceMutationInjectsExactTrustedEvidence() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.put(
                        "/api/platform/v1/home-preferences/surfaces/approval-home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return filtered.getResponse().setComplete();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.approvals.work.home-preference-update.action");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                .isEqualTo(REVISION);
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER))
                .isEqualTo(REVALIDATE_AT.toString());
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CONTEXT_HEADER)).isEqualTo("ctx-approval");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER)).isEqualTo("scope-approval");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .isEqualTo("NORMAL");
        assertThat(exchange.getResponse().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.RESPONSE_REVISION_HEADER))
                .isEqualTo(REVISION);
    }

    @Test
    void generatedHcmServicesRouteForwardsThePeopleMaterializedScope() {
        ProductSurfaceContextAggregationService authority = authority(allowedHcm());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/services/catalog?surface=hcm")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        ArgumentCaptor<ProductSurfaceContextDtos.ProductEvaluationRequest> evaluation =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.ProductEvaluationRequest.class);

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(authority).evaluateProductTrusted(any(), evaluation.capture());
        assertThat(evaluation.getValue().subject().productKey()).isEqualTo("hcm");
        assertThat(evaluation.getValue().subject().surfaceKey()).isEqualTo("hcm.personal");
        assertThat(evaluation.getValue().routeContractKey())
                .isEqualTo("route.hcm.personal.services.page");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.hcm.personal.services.page");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER))
                .isEqualTo(HCM_DERIVED_SCOPE);
        assertThat(forwarded.get().getURI().getRawQuery()).isEqualTo("surface=hcm");
    }

    @Test
    void wave2OwnerEvidenceIsStrippedAndReprojectedFromTrustedAuthority() {
        ProductSurfaceContextAggregationService authority = authority(
                allowedWave2("LEGAL_ENTITY"));
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        String subject = "10000000-0000-4000-8000-000000000041";
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/time/v1/hris/workbench/time-interpretation"
                                + "?correlationId=20000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                .header(ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER,
                        "client-forged-subject")
                .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER, "999")
                .header(ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER, "888")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER, "SELF")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                .header(ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER,
                        "TRACE_SUMMARY"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                .isEqualTo(subject);
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER))
                .matches("[1-9][0-9]*").isNotEqualTo("999");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                .matches("[1-9][0-9]*").isNotEqualTo("888");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("LEGAL_ENTITY");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                .isEqualTo("TIME_OPERATIONS");
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER)).isFalse();
    }

    @Test
    void wave2HrmAndPayPurposesAreGatewayOwned() {
        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("LEGAL_ENTITY")));
        record Case(String method, String path, String purpose, boolean command) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/people/v1/hris/assignment-events",
                        "HR_LIFECYCLE", true),
                new Case("GET", "/api/payroll/v1/workbench/settings-payroll",
                        "PAYROLL_CONFIGURATION", false));

        for (Case requestCase : cases) {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            MockServerWebExchange exchange = exchange(builder);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .isEqualTo(requestCase.purpose());
        }
    }

    @Test
    void hrm007PeopleEmploymentTranslatesOnlyTrustedResourceSetToConfigScope() {
        String path = "/api/people/v1/hris/workbench/configuration/people-employment";
        GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
        assertThat(match.uniqueRoute()).isNotNull();
        assertThat(match.uniqueRoute().routeContractKey())
                .isEqualTo("route.hcm.configuration.people-employment.data");

        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("RESOURCE_SET")));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        filter.filter(exchange(MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                        "CLIENT_FORGED")), filtered -> {
                    forwarded.set(filtered.getRequest());
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("CONFIG_SCOPE");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                .isEqualTo("HRIS_CONFIGURATION");

        for (String invalidTrustedKind : List.of("CONFIG_SCOPE", "TARGET_POPULATION")) {
            ProductSurfaceDecisionContextFilter invalidFilter = filter(authority(
                    allowedWave2(invalidTrustedKind)));
            MockServerWebExchange denied = exchange(MockServerHttpRequest.get(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest>
                    invalidForward = new AtomicReference<>();

            invalidFilter.filter(denied, filtered -> {
                invalidForward.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(invalidForward.get()).as(invalidTrustedKind).isNull();
            assertThat(denied.getResponse().getStatusCode())
                    .as(invalidTrustedKind).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(body(denied)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
        }
    }

    @Test
    void wave5SysPerTimOwnerContextIsExactAndReplacesSpoofedHeaders() {
        String id = "10000000-0000-4000-8000-000000000251";
        record Case(
                String method,
                String path,
                String route,
                String purpose,
                String population,
                boolean command) {}
        List<Case> cases = List.of(
                new Case("GET", "/api/platform/v1/admin/audit-control/events?product=HCM",
                        "route.hcm.audit.evidence.page", "HRIS_AUDIT_EVIDENCE", null, false),
                new Case("GET", "/api/platform/v1/admin/audit-control/events/" + id
                        + "?product=HCM", "route.hcm.audit.evidence-detail.data",
                        "HRIS_AUDIT_EVIDENCE", null, false),
                new Case("GET", "/api/people/v1/hris/performance/reviews/" + id,
                        "route.hcm.performance.assigned-review.data",
                        "PERFORMANCE_REVIEW", "TARGET_POPULATION", false),
                new Case("PATCH", "/api/people/v1/hris/performance/reviews/" + id,
                        "route.hcm.performance.assigned-review-draft-update.action",
                        "PERFORMANCE_REVIEW", "SELF", true),
                new Case("POST", "/api/people/v1/hris/performance/reviews/" + id + "/submit",
                        "route.hcm.performance.assigned-review-submit.action",
                        "PERFORMANCE_REVIEW", "SELF", true),
                new Case("GET", "/api/people/v1/hris/performance/workbench/multirater-surveys",
                        "route.hcm.operations.performance-multirater-surveys.page",
                        "PERFORMANCE_OPERATIONS", "TARGET_POPULATION", false),
                new Case("GET", "/api/time/v1/timecards/" + id,
                        "route.hcm.time.timecard.data", "TIME_TIMECARD", "SELF", false),
                new Case("POST", "/api/time/v1/timecards/" + id + "/corrections",
                        "route.hcm.time.timecard-correction.action",
                        "TIME_TIMECARD", "TARGET_POPULATION", true),
                new Case("POST", "/api/time/v1/timecards/" + id + "/submit",
                        "route.hcm.time.timecard-submit.action", "TIME_TIMECARD", "SELF", true),
                new Case("POST", "/api/time/v1/timecards/" + id + "/decisions",
                        "route.hcm.time.timecard-decision.action",
                        "TIME_TIMECARD", "TARGET_POPULATION", true),
                new Case("GET", "/api/time/v1/command-receipts/" + id,
                        "route.hcm.time.command-receipt.data",
                        "COMMAND_STATUS", "TARGET_POPULATION", false));

        for (Case requestCase : cases) {
            String scopeKind = requestCase.population() == null
                    ? "RESOURCE_SET" : requestCase.population();
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    allowedWave2(scopeKind)));
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange(builder), filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.route()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .containsExactly(requestCase.route());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly(requestCase.purpose());
            if (requestCase.population() == null) {
                assertThat(forwarded.get().getHeaders().containsKey(
                        ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER)).isFalse();
            } else {
                assertThat(forwarded.get().getHeaders().get(
                        ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                        .containsExactly(requestCase.population());
            }
        }
    }

    @Test
    void controlledTransferRoutesReceiveOnlyExactTrustedOwnerEvidence() {
        String subject = "10000000-0000-4000-8000-000000000041";
        String context = "psc-" + "d".repeat(64);
        record Case(
                String method,
                String routeKey,
                String populationKind,
                boolean command) {}
        List<Case> cases = List.of(
                new Case("GET", "route.hcm.management.controlled-transfer.data",
                        "CONNECTOR_SET", false),
                new Case("POST", "route.hcm.management.controlled-transfer-create.action",
                        "LEGAL_ENTITY", true));

        for (Case requestCase : cases) {
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    allowedWave2(requestCase.populationKind())));
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()),
                            "/api/platform/v1/admin/hris/file-transfers")
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "client-route")
                    .header(ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER,
                            "client-revision")
                    .header(ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER,
                            "2099-01-01T00:00:00Z")
                    .header(ProductSurfaceDecisionContextFilter.CONTEXT_HEADER, "client-context")
                    .header(ProductSurfaceDecisionContextFilter.SCOPE_HEADER, "client-scope")
                    .header(ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER,
                            "client-subject")
                    .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER,
                            "999")
                    .header(ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER,
                            "888")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER,
                            "TRANSFER_METADATA")
                    .header(ProductSurfaceDecisionContextFilter.OMITTED_FIELD_GROUPS_HEADER,
                            "TRANSFER_METADATA");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange(builder), filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.method()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .containsExactly(requestCase.routeKey());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                    .containsExactly(REVISION);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER))
                    .containsExactly(REVALIDATE_AT.toString());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.CONTEXT_HEADER))
                    .containsExactly(context);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SCOPE_HEADER))
                    .containsExactly(HCM_DERIVED_SCOPE);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                    .containsExactly(subject);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER))
                    .containsExactly("4663670355146917040");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                    .containsExactly("1991387878219104048");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .containsExactly(requestCase.populationKind());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly("HRIS_INTEGRATION_ADMIN");
            assertThat(forwarded.get().getHeaders().getFirst("X-Correlation-ID"))
                    .isEqualTo("corr-1");
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.OMITTED_FIELD_GROUPS_HEADER)).isFalse();
        }

        for (String rejectedKind : List.of("TARGET_POPULATION", "SELF")) {
            MockServerWebExchange rejected = exchange(MockServerHttpRequest.get(
                            "/api/platform/v1/admin/hris/file-transfers")
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject));
            AtomicReference<Boolean> rejectedForwarded = new AtomicReference<>(false);

            filter(authority(allowedWave2(rejectedKind))).filter(rejected, ignored -> {
                rejectedForwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(rejectedForwarded.get()).as(rejectedKind).isFalse();
            assertThat(rejected.getResponse().getStatusCode())
                    .as(rejectedKind).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(body(rejected)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
        }
    }

    @Test
    void normalHcmAuditChallengeNeverReceivesOwnerForwardingEvidence() {
        ProductSurfaceDecisionContextFilter filter = filter(authority(
                stepUpWave2("RESOURCE_SET")));
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/admin/audit-control/events?product=HCM")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(exchange)).contains("STEP_UP_REQUIRED");
    }

    @Test
    void hcmAuditChallengeIsNotExposedForInvalidQueryOrIncompleteTrustedEnvelope() {
        for (String path : List.of(
                "/api/platform/v1/admin/audit-control/events",
                "/api/platform/v1/admin/audit-control/events?product=PAYROLL",
                "/api/platform/v1/admin/audit-control/events?product=HCM&product=HCM",
                "/api/platform/v1/admin/audit-control/events?product=%ZZ")) {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041"));

            filter(authority(stepUpWave2("RESOURCE_SET")))
                    .filter(exchange, ignored -> Mono.empty()).block();

            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
            assertThat(body(exchange)).doesNotContain("STEP_UP_REQUIRED");
        }

        var complete = stepUpWave2("RESOURCE_SET");
        var withoutRevisions = new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                complete.data(), complete.contextKey(), complete.scope(), false);
        MockServerWebExchange incomplete = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/admin/audit-control/events?product=HCM")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041"));

        filter(authority(withoutRevisions)).filter(incomplete, ignored -> Mono.empty()).block();

        assertThat(incomplete.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(incomplete)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE")
                .doesNotContain("STEP_UP_REQUIRED");
    }

    @Test
    void exactTwelveGenericSessionRoutesChallengeNormalWithoutOwnerForwarding() {
        record Case(String method, String path, boolean command) {}
        List<Case> cases = List.of(
                new Case("GET",
                        "/api/platform/v1/admin/hris/configurations/config-49/versions/v49",
                        false),
                new Case("GET", "/api/platform/v1/admin/hris/configurations", false),
                new Case("GET", "/api/payroll/v1/workbench/population", false),
                new Case("GET", "/api/payroll/v1/runs/run-49", false),
                new Case("POST", "/api/time/v1/close-periods/period-49/validations", true),
                new Case("POST", "/api/time/v1/interpretation-runs", true),
                new Case("GET", "/api/time/v1/interpretation-runs/run-49/trace", false),
                new Case("POST", "/api/time/v1/close-periods/period-49/payroll-handoffs", true),
                new Case("GET", "/api/time/v1/payroll-handoffs/handoff-49", false),
                new Case("POST", "/api/time/v1/timecards/timecard-49/corrections", true),
                new Case("GET", "/api/time/v1/timecards/timecard-49", false));

        for (Case requestCase : cases) {
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    stepUpWave2("TARGET_POPULATION")));
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            MockServerWebExchange exchange = exchange(builder);
            AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

            filter.filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(body(exchange)).as(requestCase.path()).contains("STEP_UP_REQUIRED");
        }
    }

    @Test
    void v49ElevatedSessionReevaluatesAndOnlyAllowedReachesTheOwner() {
        String path = "/api/payroll/v1/runs/run-49";
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041");
        MockServerWebExchange challenged = exchange(request);
        challenged.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "ELEVATED");
        AtomicReference<Boolean> challengedForwarded = new AtomicReference<>(false);

        filter(authority(stepUpWave2("TARGET_POPULATION"))).filter(challenged, ignored -> {
            challengedForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(challengedForwarded.get()).isFalse();
        assertThat(challenged.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(challenged)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE")
                .doesNotContain("STEP_UP_REQUIRED");

        ProductSurfaceContextAggregationService allowedAuthority = authority(
                allowedWave2("TARGET_POPULATION"));
        MockServerWebExchange allowed = exchange(request);
        allowed.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "ELEVATED");
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        ArgumentCaptor<ProductSurfaceContextDtos.RequestContext> context =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.RequestContext.class);

        filter(allowedAuthority).filter(allowed, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(allowedAuthority).evaluateProductTrusted(context.capture(), any());
        assertThat(context.getValue().activeAccessMode())
                .isEqualTo(ProductSurfaceContextDtos.AccessMode.ELEVATED);
        assertThat(forwarded.get().getHeaders().get(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .containsExactly("ELEVATED");
    }

    @Test
    void payrollRunRoutesStripSpoofedEvidenceAndInjectExactTrustedOwnerContext() {
        String runId = "10000000-0000-4000-8000-000000000201";
        String receiptId = "10000000-0000-4000-8000-000000000202";
        String subject = "10000000-0000-4000-8000-000000000041";
        String context = "psc-" + "d".repeat(64);
        record Case(
                String method,
                String path,
                String routeKey,
                String purpose,
                String populationKind,
                boolean command) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/payroll/v1/runs/" + runId + "/attempts",
                        "route.hcm.operations.payroll-run-calculate.action",
                        "PAYROLL_PROCESSING", "PAY_GROUP", true),
                new Case("GET", "/api/payroll/v1/runs/" + runId,
                        "route.hcm.operations.payroll-run.data",
                        "PAYROLL_PROCESSING", "NAMED_POPULATION", false),
                new Case("POST", "/api/payroll/v1/runs/" + runId + "/approvals",
                        "route.hcm.operations.payroll-run-approve.action",
                        "PAYROLL_PROCESSING", "LEGAL_ENTITY", true),
                new Case("POST", "/api/payroll/v1/runs/" + runId + "/finalize",
                        "route.hcm.operations.payroll-run-finalize.action",
                        "PAYROLL_PROCESSING", "PAY_GROUP", true),
                new Case("GET", "/api/payroll/v1/command-receipts/" + receiptId,
                        "route.hcm.operations.payroll-command-receipt.data",
                        "COMMAND_STATUS", "ORIGINATING_COMMAND_SCOPE", false));

        for (Case requestCase : cases) {
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    allowedWave2(requestCase.populationKind())));
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "client-route")
                    .header(ProductSurfaceDecisionContextFilter.CONTEXT_HEADER, "client-context")
                    .header(ProductSurfaceDecisionContextFilter.SCOPE_HEADER, "client-scope")
                    .header(ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER,
                            "client-subject")
                    .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER,
                            "999")
                    .header(ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER,
                            "888")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER, "SELF")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER,
                            "PAYROLL_AMOUNT");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange(builder), filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .containsExactly(requestCase.routeKey());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.CONTEXT_HEADER))
                    .containsExactly(context);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SCOPE_HEADER))
                    .containsExactly(HCM_DERIVED_SCOPE);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                    .containsExactly(subject);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER))
                    .containsExactly("4663670355146917040");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                    .containsExactly("1991387878219104048");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .containsExactly(requestCase.populationKind());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly(requestCase.purpose());
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER)).isFalse();
        }
    }

    @Test
    void payrollFreshCommandsPreserveGeneratedStepUpForwarding() {
        String runId = "10000000-0000-4000-8000-000000000203";
        String subject = "10000000-0000-4000-8000-000000000041";
        List<String> paths = List.of(
                "/api/payroll/v1/runs",
                "/api/payroll/v1/runs/" + runId + "/attempts",
                "/api/payroll/v1/runs/" + runId + "/approvals",
                "/api/payroll/v1/runs/" + runId + "/finalize");

        for (String path : paths) {
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    stepUpWave2("PAY_GROUP")));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                            REVISION)
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER, "SELF"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(path).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly("PAYROLL_PROCESSING");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .containsExactly("PAY_GROUP");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                    .containsExactly(subject);
        }
    }

    @Test
    void compensationPlanningPurposeIsGatewayOwnedForEveryPublicBinding() {
        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("LEGAL_ENTITY")));
        String cycle = "10000000-0000-4000-8000-000000000101";
        String proposal = "10000000-0000-4000-8000-000000000102";
        record Case(String method, String path, boolean command) {}
        List<Case> cases = List.of(
                new Case("GET", "/api/people/v1/hris/performance/compensation-planning/cycles",
                        false),
                new Case("GET", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle, false),
                new Case("GET", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/proposals", false),
                new Case("GET", "/api/people/v1/hris/performance/compensation-planning/proposals/"
                        + proposal, false),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles",
                        true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/revisions", true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/budget-allocations", true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/proposals/" + proposal, true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/proposals/" + proposal + "/submit", true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/approve", true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/approved-snapshots", true),
                new Case("POST", "/api/people/v1/hris/performance/compensation-planning/cycles/"
                        + cycle + "/cancel", true));

        for (Case requestCase : cases) {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                            "CLIENT_FORGED");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            MockServerWebExchange exchange = exchange(builder);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .as(requestCase.path())
                    .containsExactly("COMPENSATION_PLANNING");
        }
    }

    @Test
    void navigationPolicyAdminPurposeIsGatewayOwnedAndLegalEntityEvidenceFailsClosed() {
        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("LEGAL_ENTITY")));
        String revision = "10000000-0000-4000-8000-000000000140";
        record Case(String method, String path, boolean command) {}
        List<Case> cases = List.of(
                new Case("GET",
                        "/api/platform/v1/admin/hris/navigation-policies/workspace"
                                + "?workbenchId=hr-operations&scopeKind=TENANT",
                        false),
                new Case("POST", "/api/platform/v1/admin/hris/navigation-policies/drafts",
                        true),
                new Case("PUT", "/api/platform/v1/admin/hris/navigation-policies/drafts/"
                        + revision, true),
                new Case("POST", "/api/platform/v1/admin/hris/navigation-policies/drafts/"
                        + revision + "/validate", true),
                new Case("POST", "/api/platform/v1/admin/hris/navigation-policies/drafts/"
                        + revision + "/submit", true),
                new Case("POST", "/api/platform/v1/admin/hris/navigation-policies/revisions/"
                        + revision + "/approve", true),
                new Case("POST", "/api/platform/v1/admin/hris/navigation-policies/revisions/"
                        + revision + "/publish", true),
                new Case("POST", "/api/platform/v1/admin/hris/navigation-policies/revisions/"
                        + revision + "/restore", true),
                new Case("GET",
                        "/api/platform/v2/admin/hris/navigation-policies/workspace",
                        false),
                new Case("POST", "/api/platform/v2/admin/hris/navigation-policies/drafts",
                        true),
                new Case("PUT", "/api/platform/v2/admin/hris/navigation-policies/drafts/"
                        + revision, true),
                new Case("POST", "/api/platform/v2/admin/hris/navigation-policies/drafts/"
                        + revision + "/validate", true),
                new Case("POST", "/api/platform/v2/admin/hris/navigation-policies/drafts/"
                        + revision + "/submit", true),
                new Case("POST", "/api/platform/v2/admin/hris/navigation-policies/revisions/"
                        + revision + "/approve", true),
                new Case("POST", "/api/platform/v2/admin/hris/navigation-policies/revisions/"
                        + revision + "/publish", true),
                new Case("POST", "/api/platform/v2/admin/hris/navigation-policies/revisions/"
                        + revision + "/restore", true));

        for (Case requestCase : cases) {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                            "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.VERIFIED_LEGAL_ENTITY_HEADER,
                            "10000000-0000-4000-8000-000000000999")
                    .header(ProductSurfaceDecisionContextFilter.LEGAL_ENTITY_OWNER_EVIDENCE_HEADER,
                            "CLIENT_FORGED")
                    .header(VerifiedIdentityFilter.RESPONSIBILITIES_HEADER,
                            "HRIS_DUTY_TIME_CLOSE_OPERATOR")
                    .header(VerifiedIdentityFilter.LEGAL_ENTITY_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000998")
                    .header(VerifiedIdentityFilter.TIME_GROUP_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000997");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange(builder), filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly("HRIS_SYSTEM_ADMIN");
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.VERIFIED_LEGAL_ENTITY_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.LEGAL_ENTITY_OWNER_EVIDENCE_HEADER))
                    .isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    VerifiedIdentityFilter.RESPONSIBILITIES_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    VerifiedIdentityFilter.LEGAL_ENTITY_PUBLIC_ID_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    VerifiedIdentityFilter.TIME_GROUP_PUBLIC_ID_HEADER)).isFalse();
        }
    }

    @Test
    void navigationProjectionNeverReceivesAClientForgedAdminPurpose() {
        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("LEGAL_ENTITY")));
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/hris/navigation/projection"
                                + "?workbenchId=hr-operations&contextScopeKey=scope-team-a")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                        "HRIS_SYSTEM_ADMIN"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER)).isFalse();
    }

    @Test
    void navigationV2RuntimeUsesDisjointTenantAndLegalEntityCarriers() {
        record Case(
                String path, String route, String surface, String populationKind,
                String selectedScope) { }
        String legalEntity = "10000000-0000-4000-8000-000000000045";
        List<Case> cases = List.of(
                new Case(
                        "/api/platform/v2/hris/navigation/projection"
                                + "?navigationScope=TENANT",
                        "route.hcm.shell.navigation-aggregate.data",
                        "hcm.utility", "SELF", null),
                new Case(
                        "/api/platform/v2/hris/navigation/deep-link-decision"
                                + "?navigationScope=TENANT&nodeId=HRIS-NODE-HOME"
                                + "&requestedPath=%2Fhr&revisionPin=nav-45",
                        "route.hcm.shell.navigation-deep-link-decision.data",
                        "hcm.utility", "SELF", null),
                new Case(
                        "/api/platform/v2/hris/navigation/projection"
                                + "?navigationScope=LEGAL_ENTITY"
                                + "&legalEntityPublicId=" + legalEntity
                                + "&contextScopeKey=" + HCM_DERIVED_SCOPE,
                        "route.hcm.shell.navigation-aggregate-legal-entity.data",
                        "hcm.operations", "TARGET_POPULATION", HCM_DERIVED_SCOPE),
                new Case(
                        "/api/platform/v2/hris/navigation/deep-link-decision"
                                + "?navigationScope=LEGAL_ENTITY"
                                + "&legalEntityPublicId=" + legalEntity
                                + "&nodeId=HRIS-NODE-HOME&requestedPath=%2Fhr"
                                + "&revisionPin=nav-45&contextScopeKey="
                                + HCM_DERIVED_SCOPE,
                        "route.hcm.shell.navigation-deep-link-decision-legal-entity.data",
                        "hcm.operations", "TARGET_POPULATION", HCM_DERIVED_SCOPE));
        for (Case requestCase : cases) {
            java.net.URI uri = java.net.URI.create(requestCase.path());
            GeneratedProductRouteCatalog.Match catalogMatch = catalog.match(
                    "GET", uri.getPath(), uri.getRawQuery());
            assertThat(catalogMatch.status()).as(requestCase.path())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(catalogMatch.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(requestCase.route());
                assertThat(route.surfaceKey()).isEqualTo(requestCase.surface());
            });
            ProductSurfaceContextAggregationService authority = authority(
                    allowedWave2(requestCase.populationKind()));
            ProductSurfaceDecisionContextFilter filter = filter(authority);
            String path = requestCase.path();
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                            "HRIS_SYSTEM_ADMIN"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest>
                    forwarded = new AtomicReference<>();
            ArgumentCaptor<ProductSurfaceContextDtos.ProductEvaluationRequest> evaluation =
                    ArgumentCaptor.forClass(
                            ProductSurfaceContextDtos.ProductEvaluationRequest.class);

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            verify(authority).evaluateProductTrusted(any(), evaluation.capture());
            assertThat(forwarded.get()).as(path).isNotNull();
            assertThat(evaluation.getValue().routeContractKey())
                    .isEqualTo(requestCase.route());
            assertThat(evaluation.getValue().subject().surfaceKey())
                    .isEqualTo(requestCase.surface());
            assertThat(evaluation.getValue().contextScopeKey())
                    .isEqualTo(requestCase.selectedScope());
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .isEqualTo(requestCase.route());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .isEqualTo(requestCase.populationKind());
            assertThat(forwarded.get().getURI().getRawQuery())
                    .doesNotContain("contextScopeKey");
        }
    }

    @Test
    void navigationV2ScopeDiscriminatorAndTrustedPopulationMismatchFailClosed() {
        String legalEntity = "10000000-0000-4000-8000-000000000045";
        for (String path : List.of(
                "/api/platform/v2/hris/navigation/projection",
                "/api/platform/v2/hris/navigation/projection"
                        + "?navigationScope=TENANT&legalEntityPublicId=" + legalEntity,
                "/api/platform/v2/hris/navigation/projection"
                        + "?navigationScope=LEGAL_ENTITY")) {
            java.net.URI uri = java.net.URI.create(path);
            assertThat(catalog.match("GET", uri.getPath(), uri.getRawQuery()).status())
                    .as(path).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.INVALID);
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
            filter(authority(allowedWave2("SELF")))
                    .filter(exchange, ignored -> Mono.empty()).block();
            assertThat(exchange.getResponse().getStatusCode()).as(path)
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        record Mismatch(String path, String returnedKind) { }
        for (Mismatch mismatch : List.of(
                new Mismatch(
                        "/api/platform/v2/hris/navigation/projection"
                                + "?navigationScope=TENANT", "TARGET_POPULATION"),
                new Mismatch(
                        "/api/platform/v2/hris/navigation/projection"
                                + "?navigationScope=LEGAL_ENTITY"
                                + "&legalEntityPublicId=" + legalEntity
                                + "&contextScopeKey=" + HCM_DERIVED_SCOPE,
                        "SELF"))) {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(mismatch.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
            filter(authority(allowedWave2(mismatch.returnedKind())))
                    .filter(exchange, ignored -> Mono.empty()).block();
            assertThat(exchange.getResponse().getStatusCode()).as(mismatch.path())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void selectedScopeIsLiveEvaluatedAndConsumedBeforeOwnerForwarding() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/admin/workflows"
                                + "?view=reference&contextScopeKey=scope-team-a")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        ArgumentCaptor<ProductSurfaceContextDtos.ProductEvaluationRequest> evaluation =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.ProductEvaluationRequest.class);

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(authority).evaluateProductTrusted(any(), evaluation.capture());
        assertThat(evaluation.getValue().contextScopeKey()).isEqualTo("scope-team-a");
        assertThat(forwarded.get().getURI().getRawQuery()).isEqualTo("view=reference");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER)).isEqualTo("scope-approval");
    }

    @Test
    void duplicateBlankAndOversizedScopeSelectionsFailBeforeAuthority() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        for (String query : List.of(
                "contextScopeKey=scope-a&contextScopeKey=scope-b",
                "contextScopeKey=",
                "contextScopeKey=%20",
                "contextScopeKey=" + "a".repeat(201))) {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                            "/api/approvals/v1/admin/forms?" + query)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));

            filter.filter(exchange, ignored -> Mono.empty()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(exchange)).contains("INVALID_SCOPE_SELECTION");
        }
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void concurrentTabSelectionsRemainRequestLocal() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        for (String scope : List.of("scope-team-a", "scope-team-b")) {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                            "/api/approvals/v1/admin/forms?contextScopeKey=" + scope)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
            filter.filter(exchange, ignored -> Mono.empty()).block();
        }
        ArgumentCaptor<ProductSurfaceContextDtos.ProductEvaluationRequest> evaluations =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.ProductEvaluationRequest.class);
        verify(authority, times(2)).evaluateProductTrusted(any(), evaluations.capture());
        assertThat(evaluations.getAllValues())
                .extracting(ProductSurfaceContextDtos.ProductEvaluationRequest::contextScopeKey)
                .containsExactly("scope-team-a", "scope-team-b");
    }

    @Test
    void expectedRevisionMustBeExactlyOneCanonicalHeader() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.put(
                        "/api/platform/v1/home-preferences/surfaces/approval-home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "111")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION, REVISION));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(exchange)).contains("DECISION_REVISION_CONFLICT");
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void staleAuthorityRevisionNeverReachesTheOwnerService() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.put(
                        "/api/platform/v1/home-preferences/surfaces/approval-home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        "psr-" + "b".repeat(64)));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(exchange)).contains("DECISION_REVISION_CONFLICT");
        assertThat(exchange.getResponse().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.RESPONSE_REVISION_HEADER))
                .isEqualTo(REVISION);
    }

    @Test
    void legacyAndShadowStatesBypassPepButStripAllInternalEvidence() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        for (String state : List.of("000", "100")) {
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(
                            "/api/approvals/v1/requests")
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, state)
                    .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "spoofed")
                    .header(ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER, REVISION)
                    .header(ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER,
                            REVALIDATE_AT.toString())
                    .header(ProductSurfaceDecisionContextFilter.CONTEXT_HEADER, "spoofed")
                    .header(ProductSurfaceDecisionContextFilter.SCOPE_HEADER, "spoofed")
                    .header(ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER,
                            "ELEVATED")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.VERIFIED_LEGAL_ENTITY_HEADER,
                            "10000000-0000-4000-8000-000000000999")
                    .header(ProductSurfaceDecisionContextFilter.LEGAL_ENTITY_OWNER_EVIDENCE_HEADER,
                            "CLIENT_FORGED"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.VERIFIED_LEGAL_ENTITY_HEADER)).isFalse();
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.LEGAL_ENTITY_OWNER_EVIDENCE_HEADER))
                    .isFalse();
        }
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void exactTenantFeatureRouteFamilyAlwaysEvaluatesAuthAtRollout000And100() {
        record RouteCase(String method, String path) {}
        List<RouteCase> routes = List.of(
                new RouteCase("GET", "/api/people/v1/hris/organizations/graph"),
                new RouteCase("GET", "/api/platform/v1/hris/workbench/settings-operations"),
                new RouteCase("GET", "/api/platform/v1/admin/hris/operations/exceptions"),
                new RouteCase("GET", "/api/platform/v1/admin/hris/operations/exceptions/ex-1"),
                new RouteCase("GET", "/api/payroll/v1/workbench/population"),
                new RouteCase("POST", "/api/payroll/v1/runs"),
                new RouteCase("GET", "/api/time/v1/hris/workbench/time-command-center"),
                new RouteCase("GET", "/api/time/v1/hris/workbench/time-daily-close"),
                new RouteCase("GET", "/api/time/v1/hris/workbench/time-monthly-close"),
                new RouteCase("POST", "/api/time/v1/close-periods/period-1/validations"),
                new RouteCase("POST", "/api/time/v1/close-periods/period-1/close"),
                new RouteCase("POST", "/api/time/v1/close-periods/period-1/reopen"),
                new RouteCase("POST", "/api/time/v1/close-periods/period-1/payroll-handoffs"),
                new RouteCase("GET", "/api/time/v1/payroll-handoffs/handoff-1"));
        ProductSurfaceContextAggregationService authority = authority(activationRequired(
                "FEATURE_ACTIVATION_REQUIRED"));
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        int evaluations = 0;

        for (String rollout : List.of("000", "100")) {
            for (String tenant : List.of("7", "91")) {
                for (RouteCase route : routes) {
                    MockServerWebExchange exchange = exchange(MockServerHttpRequest.method(
                                    HttpMethod.valueOf(route.method()), route.path())
                            .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, rollout)
                            .header(VerifiedIdentityFilter.USER_HEADER, "41")
                            .header(VerifiedIdentityFilter.TENANT_HEADER, tenant));
                    AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

                    filter.filter(exchange, ignored -> {
                        forwarded.set(true);
                        return Mono.empty();
                    }).block();

                    assertThat(forwarded.get()).as(route.path()).isFalse();
                    assertThat(exchange.getResponse().getStatusCode()).as(route.path())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(body(exchange)).as(route.path())
                            .contains("FEATURE_ACTIVATION_REQUIRED");
                    evaluations++;
                }
            }
        }

        verify(authority, times(evaluations)).evaluateProductTrusted(any(), any());
    }

    @Test
    void activationRequiredReasonsAre403AndAuthorityUnavailableIs503WithoutOwnerCalls() {
        for (String reason : List.of(
                "FEATURE_ACTIVATION_REQUIRED",
                "TENANT_FEATURE_ENROLLMENT_REQUIRED",
                "TENANT_APP_ADOPTION_REQUIRED")) {
            MockServerWebExchange denied = exchange(MockServerHttpRequest.get(
                            "/api/platform/v1/hris/workbench/settings-operations")
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
            AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

            filter(authority(activationRequired(reason))).filter(denied, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(reason).isFalse();
            assertThat(denied.getResponse().getStatusCode()).as(reason)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(body(denied)).as(reason).contains(reason);
        }

        MockServerWebExchange unavailable = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/hris/workbench/settings-operations")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "100")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<Boolean> unavailableForwarded = new AtomicReference<>(false);

        filter(authority(authorityUnavailable())).filter(unavailable, ignored -> {
            unavailableForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(unavailableForwarded.get()).isFalse();
        assertThat(unavailable.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(unavailable)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void governedRouteForwardsOnlyWithNonblankTenantControlEvidence() {
        var active = allowedWave2("TARGET_POPULATION");
        var legacy = new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                active.data(),
                active.contextKey(),
                active.scope(),
                new ProductSurfaceContextDtos.SourceRevisions(
                        "auth-v38", "policy-v38", "relationship-v1", "population-v1", null),
                false);
        MockServerWebExchange rejected = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/hris/workbench/settings-operations")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041"));
        AtomicReference<Boolean> rejectedForwarded = new AtomicReference<>(false);

        filter(authority(legacy)).filter(rejected, ignored -> {
            rejectedForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(rejectedForwarded.get()).isFalse();
        assertThat(rejected.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        MockServerWebExchange admitted = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/hris/workbench/settings-operations")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter(authority(active)).filter(admitted, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.hcm.configuration.settings-operations.page");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                .isEqualTo(REVISION);
    }

    @Test
    void homeShadowRoutesReceiveRecipientBoundAuthorityEvidence() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);

        MockServerWebExchange read = exchange(MockServerHttpRequest.get(
                        "/api/platform/v2/home?deviceClass=DESKTOP_STANDARD")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "100")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwardedRead =
                new AtomicReference<>();
        filter.filter(read, filtered -> {
            forwardedRead.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwardedRead.get()).isNotNull();
        assertThat(forwardedRead.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.workplace.work.home-read-model.data");
        assertThat(forwardedRead.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                .isEqualTo(REVISION);

        MockServerWebExchange receipt = exchange(MockServerHttpRequest.post(
                        "/api/platform/v2/home/shadow-receipts")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "100")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest>
                forwardedReceipt = new AtomicReference<>();
        filter.filter(receipt, filtered -> {
            forwardedReceipt.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwardedReceipt.get()).isNotNull();
        assertThat(forwardedReceipt.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.workplace.work.home-shadow-receipt.action");
        assertThat(forwardedReceipt.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER))
                .isEqualTo(REVISION);
        verify(authority, times(2)).evaluateProductTrusted(any(), any());
    }

    @Test
    void homeShadowMutationWithoutExpectedRevisionFailsClosed() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange receipt = exchange(MockServerHttpRequest.post(
                        "/api/platform/v2/home/shadow-receipts")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "100")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));

        filter.filter(receipt, ignored -> Mono.empty()).block();

        assertThat(receipt.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(receipt)).contains("DECISION_REVISION_CONFLICT");
    }

    @Test
    void contractLessRaw110RouteFailsClosedWithoutCompatibilityDowngrade() {
        ProductSurfaceContextAggregationService authority = authority(productNotRegistered());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(
                        "/api/people/v1/workforce/exports")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, filtered -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void ownerFlattenedProductAbsenceCannotDowngradeEnforcement() {
        ProductSurfaceContextAggregationService authority = authority(
                productNotRegistered(false));
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(
                        "/api/people/v1/workforce/exports")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(exchange)).contains("PRODUCT_NOT_REGISTERED");
    }

    @Test
    void contractLessRaw111RouteFailsClosedAsAuthorityUnavailable() {
        ProductSurfaceContextAggregationService authority = authority(productNotRegistered());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/people/v1/workforce/exports/datasets")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "111")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void participatingProductRouteDriftRemainsFailClosedInEnforcement() {
        ProductSurfaceContextAggregationService authority = authority(routeNotRegistered());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/admin/workflows")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(exchange)).contains("ROUTE_NOT_REGISTERED");
    }

    @Test
    void activeSupportEvidenceCannotDowngradeToNormalMode() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(SupportSessionContextFilter.SUPPORT_SESSION_HEADER, "support-9")
                .header(SupportSessionContextFilter.SUPPORT_REVISION_HEADER, "support-v3")
                .header(SupportSessionContextFilter.SUPPORT_SCOPES_HEADER, "read:a,read:b")
                .header(ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL"));
        ArgumentCaptor<ProductSurfaceContextDtos.RequestContext> context =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.RequestContext.class);
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(authority).evaluateProductTrusted(context.capture(), any());
        assertThat(context.getValue().activeAccessMode())
                .isEqualTo(ProductSurfaceContextDtos.AccessMode.PROVIDER_SUPPORT);
        assertThat(context.getValue().supportSessionRef()).isEqualTo("support-9");
        assertThat(context.getValue().supportScopes()).containsExactly("read:a", "read:b");
        assertThat(forwarded.get().getHeaders().get(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .containsExactly("PROVIDER_SUPPORT");
    }

    @Test
    void clientCannotForgeElevatedAccessModeOnGovernedRoute() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER,
                        "ELEVATED"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        ArgumentCaptor<ProductSurfaceContextDtos.RequestContext> context =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.RequestContext.class);

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(authority).evaluateProductTrusted(context.capture(), any());
        assertThat(context.getValue().activeAccessMode())
                .isEqualTo(ProductSurfaceContextDtos.AccessMode.NORMAL);
        assertThat(forwarded.get().getHeaders().get(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .containsExactly("NORMAL");
    }

    @Test
    void malformedServerOwnedAccessModeFailsClosedBeforeAuthorityEvaluation() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        exchange.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "elevated");
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter(authority).filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        verify(authority, never()).evaluateProductTrusted(any(), any());
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void stepUpMayPassOnlyForAGeneratedHighRiskRoute() {
        ProductSurfaceContextAggregationService authority = authority(stepUp());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange high = exchange(MockServerHttpRequest.post(
                        "/api/approvals/v1/admin/workflows/"
                                + "11111111-1111-1111-1111-111111111111/publish")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(high, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER)).isEqualTo("scope-approval");

        MockServerWebExchange routine = exchange(MockServerHttpRequest.post(
                        "/api/approvals/v1/requests")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION));
        filter.filter(routine, ignored -> Mono.empty()).block();
        assertThat(routine.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void issuerPreservesOnlyOneExpectedRevisionWithoutEnteringDomainPepLoop() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(
                        "/api/auth/product-surface-step-up-challenges")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION)
                .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "spoofed"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().get(
                ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER))
                .containsExactly(REVISION);
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER)).isFalse();
        verify(authority, never()).evaluateProductTrusted(any(), any());

        MockServerWebExchange missing = exchange(MockServerHttpRequest.post(
                "/api/auth/product-surface-step-up-challenges"));
        filter.filter(missing, ignored -> Mono.empty()).block();
        assertThat(missing.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void exactLegacyWorkforceAccessRequestsBypassPepAndStripAllDecisionEvidence() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        List<MockServerHttpRequest.BaseBuilder<?>> requests = List.of(
                MockServerHttpRequest.get(
                        "/api/people/v1/admin/workforce/access-policies"),
                MockServerHttpRequest.get(
                        "/api/people/v1/admin/workforce/access-policies/organizations"),
                MockServerHttpRequest.post(
                        "/api/people/v1/admin/workforce/access-policies"),
                MockServerHttpRequest.patch(
                        "/api/people/v1/admin/workforce/access-policies/policy-7/revoke"));

        for (MockServerHttpRequest.BaseBuilder<?> request : requests) {
            MockServerWebExchange exchange = exchange(request
                    .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION)
                    .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "spoofed-route")
                    .header(ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER, REVISION)
                    .header(ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER,
                            REVALIDATE_AT.toString())
                    .header(ProductSurfaceDecisionContextFilter.CONTEXT_HEADER, "spoofed-context")
                    .header(ProductSurfaceDecisionContextFilter.SCOPE_HEADER, "spoofed-scope")
                    .header(ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER,
                            "ELEVATED"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isNotNull();
            for (String header : List.of(
                    ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER,
                    ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER,
                    ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER,
                    ProductSurfaceDecisionContextFilter.CONTEXT_HEADER,
                    ProductSurfaceDecisionContextFilter.SCOPE_HEADER,
                    ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER)) {
                assertThat(forwarded.get().getHeaders().containsKey(header)).as(header).isFalse();
            }
        }
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void legacyWorkforceAccessRequestsRejectProductScopeSelectionAndPathDrift() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange scoped = exchange(MockServerHttpRequest.get(
                "/api/people/v1/admin/workforce/access-policies?contextScopeKey=scope-hcm"));
        MockServerWebExchange drifted = exchange(MockServerHttpRequest.get(
                "/api/people/v1/admin/workforce/access-policies/organizations/"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(scoped, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        filter.filter(drifted, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(scoped.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(scoped)).contains("INVALID_SCOPE_SELECTION");
        assertThat(drifted.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(drifted)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void incrementalWorkplaceReadWithoutTrustedRolloutFailsClosed() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                "/api/platform/v1/workplace/bookings"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void invalidRolloutAndUnknownProductRouteFailClosedButNonProductPasses() {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange invalidState = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "010"));
        filter.filter(invalidState, ignored -> Mono.empty()).block();
        assertThat(invalidState.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        MockServerWebExchange unknown = exchange(MockServerHttpRequest.get(
                "/api/approvals/v1/not-registered"));
        filter.filter(unknown, ignored -> Mono.empty()).block();
        assertThat(unknown.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        MockServerWebExchange nonProduct = exchange(MockServerHttpRequest.get("/api/auth/me"));
        AtomicReference<Boolean> passed = new AtomicReference<>(false);
        filter.filter(nonProduct, ignored -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        assertThat(passed.get()).isTrue();
    }

    @Test
    void recordsTheGeneratedRouteTemplateBeforeCommittingAnAuthorityDenial() {
        AtomicReference<GatewayDenialAuditSink.Denial> evidence = new AtomicReference<>();
        ProductSurfaceDecisionContextFilter filter = new ProductSurfaceDecisionContextFilter(
                authority(routeNotRegistered()), catalog, objectMapper,
                (exchange, denial) -> {
                    evidence.set(denial);
                    return Mono.empty();
                });
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/admin/workflows")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(evidence.get().routeTemplate())
                .isEqualTo("/api/approvals/v1/admin/workflows");
        assertThat(evidence.get().denialCode()).isEqualTo("ROUTE_NOT_REGISTERED");
    }

    @Test
    void convertsAuthorityDenialToServiceUnavailableWhenEvidenceSinkFails() {
        ProductSurfaceDecisionContextFilter filter = new ProductSurfaceDecisionContextFilter(
                authority(routeNotRegistered()), catalog, objectMapper,
                (exchange, denial) -> Mono.error(new IllegalStateException("sink unavailable")));
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/approvals/v1/admin/workflows")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUDIT_EVIDENCE_UNAVAILABLE");
    }

    private ProductSurfaceDecisionContextFilter filter(
            ProductSurfaceContextAggregationService authority) {
        return new ProductSurfaceDecisionContextFilter(authority, catalog, objectMapper);
    }

    private ProductSurfaceContextAggregationService authority(
            ProductSurfaceContextAggregationService.TrustedProductEvaluation result) {
        ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        when(authority.evaluateProductTrusted(any(), any())).thenReturn(Mono.just(result));
        return authority;
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation allowed() {
        ProductSurfaceContextDtos.EffectiveScope scope = scope();
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        "ctx-approval", "approvals", "approvals.work", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.ENTITLEMENT,
                        "APP.APPROVALS", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-1", scope, false, REVALIDATE_AT, null,
                        null, null, REVALIDATE_AT),
                "ctx-approval", scope, sourceRevisions(), false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedHcm() {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        HCM_DERIVED_SCOPE, "SELF", "Self", true, false, REVALIDATE_AT);
        String contextKey = "psc-" + "b".repeat(64);
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        contextKey, "hcm", "hcm.personal", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.RELATIONSHIP,
                        "APP.HCM", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-hcm-services", scope, false, REVALIDATE_AT,
                        null, null, null, REVALIDATE_AT),
                contextKey, scope, sourceRevisions(), false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedDwaion() {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        "scope-dwaion-self", "SELF", "Self", true, false, REVALIDATE_AT);
        String contextKey = "psc-" + "c".repeat(64);
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        contextKey, "dwaion", "dwaion.work", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.ENTITLEMENT,
                        "APP.ASK", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-dwaion", scope, false, REVALIDATE_AT,
                        null, null, null, REVALIDATE_AT),
                contextKey, scope, sourceRevisions(), false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation allowedWave2(
            String scopeKind) {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        HCM_DERIVED_SCOPE, scopeKind, scopeKind, true, false, REVALIDATE_AT);
        String contextKey = "psc-" + "d".repeat(64);
        ProductSurfaceContextDtos.EffectiveContext context =
                new ProductSurfaceContextDtos.EffectiveContext(
                        contextKey, "hcm", "hcm.operations", "work",
                        ProductSurfaceContextDtos.AccessMode.NORMAL,
                        ProductSurfaceContextDtos.AccessSource.RELATIONSHIP,
                        "APP.HCM", List.of(), List.of(scope), REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ALLOWED, "ALLOWED", REVISION,
                        context, "grant-hcm-wave2", scope, false, REVALIDATE_AT,
                        null, null, null, REVALIDATE_AT),
                contextKey,
                scope,
                sourceRevisions(),
                false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation stepUp() {
        ProductSurfaceContextDtos.EffectiveScope scope = scope();
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED,
                        "STEP_UP_REQUIRED", REVISION, null, null, null, null,
                        REVALIDATE_AT, null, "urn:dwp:acr:mfa",
                        "STEPUP-MGMT-HIGH-V1", REVALIDATE_AT),
                "ctx-approval", scope, sourceRevisions(), false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation stepUpWave2(
            String scopeKind) {
        ProductSurfaceContextDtos.EffectiveScope scope =
                new ProductSurfaceContextDtos.EffectiveScope(
                        HCM_DERIVED_SCOPE, scopeKind, scopeKind, true, false, REVALIDATE_AT);
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED,
                        "STEP_UP_REQUIRED", REVISION, null, null, null, null,
                        REVALIDATE_AT, null, "urn:dwp:acr:mfa",
                        "STEPUP-MGMT-HIGH-V1", REVALIDATE_AT),
                "psc-" + "d".repeat(64),
                scope,
                sourceRevisions(),
                false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation routeNotRegistered() {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                        "ROUTE_NOT_REGISTERED", REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
                sourceRevisions(),
                false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation productNotRegistered() {
        return productNotRegistered(true);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation productNotRegistered(
            boolean authRouteProductNotRegistered) {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                        "PRODUCT_NOT_REGISTERED", REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
                sourceRevisions(),
                authRouteProductNotRegistered);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation activationRequired(
            String reasonCode) {
        return deniedEvaluation(ProductSurfaceContextDtos.Decision.ACTIVATION_REQUIRED, reasonCode);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation authorityUnavailable() {
        return deniedEvaluation(
                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE,
                "AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation deniedEvaluation(
            ProductSurfaceContextDtos.Decision decision,
            String reasonCode) {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        decision, reasonCode, REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
                sourceRevisions(),
                false);
    }

    private ProductSurfaceContextDtos.SourceRevisions sourceRevisions() {
        return new ProductSurfaceContextDtos.SourceRevisions(
                "auth-v38",
                "policy-v38",
                "relationship-v1",
                "population-v1",
                "tenant-control-activation-1",
                null);
    }

    private ProductSurfaceContextDtos.EffectiveScope scope() {
        return new ProductSurfaceContextDtos.EffectiveScope(
                "scope-approval", "APP_RESOURCE_SET", "Approvals", true, false,
                REVALIDATE_AT);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request
                .header("X-Correlation-ID", "corr-1").build());
    }

    private String body(MockServerWebExchange exchange) {
        DataBuffer buffer = DataBufferUtils.join(exchange.getResponse().getBody()).block();
        if (buffer == null) return "";
        byte[] bytes = new byte[buffer.readableByteCount()];
        try {
            buffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
