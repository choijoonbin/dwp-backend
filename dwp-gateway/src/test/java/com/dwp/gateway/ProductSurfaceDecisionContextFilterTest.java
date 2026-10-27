package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.SupportSessionContextFilter;
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

class ProductSurfaceDecisionContextFilterTest extends ProductSurfaceDecisionContextFilterTestSupport {

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
        assertThat(catalog.match("GET",
                "/api/people/v1/hris/workbench/operations/exit/return").routes())
                .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                .containsExactlyInAnyOrder(
                        "route.hcm.operations.exit-return.page",
                        "route.hcm.operations.exit-return.data");
        assertThat(catalog.match("POST", "/api/people/v1/hris/separation-cases").routes())
                .extracting(GeneratedProductRouteCatalog.Route::routeContractKey)
                .containsExactly("route.hcm.operations.separation-case.action");
        record RouteCase(String method, String path, boolean pay003Command) {
            RouteCase(String method, String path) {
                this(method, path, false);
            }
        }
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
                new RouteCase("GET", "/api/time/v1/payroll-handoffs/handoff-1"),
                new RouteCase("GET", "/api/payroll/v1/workbench/configuration/statutory"),
                new RouteCase("GET", "/api/payroll/v1/workbench/pay-tax-social-insurance"),
                new RouteCase("GET", "/api/payroll/v1/workbench/pay-retirement"),
                new RouteCase("GET", "/api/people/v1/hris/workbench/operations/exit/return"),
                new RouteCase("POST", "/api/people/v1/hris/separation-cases"),
                new RouteCase("POST", "/api/payroll/v1/country-packs/"
                        + "10000000-0000-4000-8000-000000000054/activate", true));
        ProductSurfaceContextAggregationService authority = authority(activationRequired(
                "FEATURE_ACTIVATION_REQUIRED"));
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        int evaluations = 0;

        for (String rollout : List.of("000", "100")) {
            for (String tenant : List.of("7", "91")) {
                for (RouteCase route : routes) {
                    MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(
                                    HttpMethod.valueOf(route.method()), route.path())
                            .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, rollout)
                            .header(VerifiedIdentityFilter.USER_HEADER, "41")
                            .header(VerifiedIdentityFilter.TENANT_HEADER, tenant);
                    if (route.pay003Command()) {
                        request.header(
                                ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                REVISION).header("If-Match", "\"7\"");
                    }
                    MockServerWebExchange exchange = exchange(request);
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


}
