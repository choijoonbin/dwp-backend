package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceDecisionNavigationContextTest extends ProductSurfaceDecisionContextFilterTestSupport {

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

}
