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
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
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
                            "ELEVATED"));
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
        }
        verify(authority, never()).evaluateProductTrusted(any(), any());
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

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().get(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .containsExactly("NORMAL");
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
    void incrementalWorkplaceReadSiblingBypassesProductDecisionContext() {
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

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER)).isFalse();
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
                "ctx-approval", scope, false);
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
                contextKey, scope, false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation stepUp() {
        ProductSurfaceContextDtos.EffectiveScope scope = scope();
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED,
                        "STEP_UP_REQUIRED", REVISION, null, null, null, null,
                        REVALIDATE_AT, null, "urn:dwp:acr:mfa",
                        "STEPUP-MGMT-HIGH-V1", REVALIDATE_AT),
                "ctx-approval", scope, false);
    }

    private ProductSurfaceContextAggregationService.TrustedProductEvaluation routeNotRegistered() {
        return new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                new ProductSurfaceContextDtos.ProductEvaluationData(
                        ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                        "ROUTE_NOT_REGISTERED", REVISION, null, null, null, null,
                        null, null, null, null, null),
                null,
                null,
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
                authRouteProductNotRegistered);
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
