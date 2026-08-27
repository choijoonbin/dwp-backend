package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.ProviderDataPlaneBoundaryFilter;
import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.VerifiedIdentity;
import com.dwp.gateway.security.VerifiedSupportAccess;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderDataPlaneBoundaryFilterTest {

    private final ProviderDataPlaneBoundaryFilter boundary =
            new ProviderDataPlaneBoundaryFilter();

    @Test
    void providerBoundaryRunsAfterIdentityAndSupportButBeforeProductAuthority() {
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.empty());
        SupportSessionContextFilter support = new SupportSessionContextFilter(
                (request, token) -> Mono.empty());

        assertThat(identity.getOrder()).isLessThan(support.getOrder());
        assertThat(support.getOrder()).isLessThan(boundary.getOrder());
        // ProductSurfaceRolloutHeaderFilter currently starts at -94. Keeping the
        // provider boundary before it prevents authority availability from
        // changing an ambient provider denial from 403 to 503.
        assertThat(boundary.getOrder()).isLessThan(-94);
    }

    @Test
    void sortedRuntimeChainDeniesProviderBeforeUnavailableProductAuthority() {
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.just(
                new VerifiedIdentity("900001", "1", List.of("PROVIDER_ADMIN"), "PROVIDER")));
        SupportSessionContextFilter support = new SupportSessionContextFilter(
                (request, token) -> Mono.empty());
        AtomicBoolean authorityInvoked = new AtomicBoolean();
        GlobalFilter unavailableAuthority = new UnavailableProductAuthorityFilter(
                authorityInvoked);
        List<GlobalFilter> filters = new ArrayList<>(List.of(
                unavailableAuthority, boundary, support, identity));
        AnnotationAwareOrderComparator.sort(filters);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/people/v1/admin/workforce")
                .header("X-Tenant-ID", "1")
                .build());

        invoke(filters, 0, exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(authorityInvoked).isFalse();
    }

    @Test
    void deniesProviderIdentityFromAmbientTenantDataPlaneAccess() {
        MockServerWebExchange exchange = exchange(
                "/api/platform/v1/home", "PROVIDER_ADMIN", null);
        AtomicBoolean forwarded = new AtomicBoolean();

        boundary.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forwarded).isFalse();
    }

    @Test
    void commitsTheProviderDenialOnlyAfterCentralEvidenceIsAccepted() {
        AtomicReference<GatewayDenialAuditSink.Denial> evidence = new AtomicReference<>();
        ProviderDataPlaneBoundaryFilter audited = new ProviderDataPlaneBoundaryFilter(
                (exchange, denial) -> {
                    evidence.set(denial);
                    return Mono.empty();
                });
        MockServerWebExchange exchange = exchange(
                "/api/platform/v1/home", "PROVIDER_ADMIN", null);

        audited.filter(exchange, ignored -> Mono.error(
                new AssertionError("denied request must not be forwarded"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(evidence.get().policyId()).isEqualTo("PROVIDER_DATA_PLANE_BOUNDARY_V1");
        assertThat(evidence.get().denialCode())
                .isEqualTo("PROVIDER_AMBIENT_TENANT_ACCESS_DENIED");
    }

    @Test
    void returnsServiceUnavailableWithoutCommittingAForbiddenWhenAuditIsUnavailable() {
        ProviderDataPlaneBoundaryFilter audited = new ProviderDataPlaneBoundaryFilter(
                (exchange, denial) -> Mono.error(new IllegalStateException("sink unavailable")));
        MockServerWebExchange exchange = exchange(
                "/api/platform/v1/home", "PROVIDER_ADMIN", null);

        audited.filter(exchange, ignored -> Mono.error(
                new AssertionError("denied request must not be forwarded"))).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void providerRoleTakesPrecedenceOverAConflictingTenantRole() {
        MockServerWebExchange exchange = exchange(
                "/api/people/v1/people", "ADMIN,PROVIDER_ADMIN", null);

        boundary.filter(exchange, ignored -> Mono.error(
                new AssertionError("mixed identity must not reach data plane"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void durableProviderPlaneStillBlocksARolelessDeprovisionedOperator() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/home")
                .header(VerifiedIdentityFilter.USER_HEADER, "900001")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .header(VerifiedIdentityFilter.ROLES_HEADER, "")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .build());

        boundary.filter(exchange, ignored -> Mono.error(
                new AssertionError("role removal must not convert the provider principal"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void permitsProviderControlAndExactAccountSelfServicePaths() {
        for (RequestCase requestCase : List.of(
                new RequestCase(HttpMethod.GET, "/api/provider/v1/tenants"),
                new RequestCase(HttpMethod.GET, "/api/auth/me"),
                new RequestCase(HttpMethod.GET, "/api/auth/permissions"),
                new RequestCase(HttpMethod.GET, "/api/auth/sessions"),
                new RequestCase(HttpMethod.PATCH, "/api/auth/me/locale"),
                new RequestCase(HttpMethod.POST, "/api/auth/session/refresh"),
                new RequestCase(HttpMethod.POST, "/api/auth/sessions/logout-others"),
                new RequestCase(HttpMethod.POST, "/api/agent/v1/plans/preview"),
                new RequestCase(HttpMethod.DELETE,
                        "/api/auth/sessions/40000000-0000-0000-0000-000000000001"))) {
            assertProviderForwarded(requestCase);
        }
    }

    @Test
    void deniesProviderFromUnknownOrTenantAuthSurfaces() {
        for (RequestCase requestCase : List.of(
                new RequestCase(HttpMethod.POST, "/api/auth/me"),
                new RequestCase(HttpMethod.GET, "/api/auth/session/refresh"),
                new RequestCase(HttpMethod.GET, "/api/auth/idp"),
                new RequestCase(HttpMethod.GET, "/api/auth/me/policy"),
                new RequestCase(HttpMethod.DELETE, "/api/auth/sessions/not-a-uuid"),
                new RequestCase(HttpMethod.GET, "/api/auth/future-account-feature"),
                new RequestCase(HttpMethod.GET,
                        "/api/auth/work/access-review-items/40000000-0000-0000-0000-000000000002"),
                new RequestCase(HttpMethod.GET,
                        "/api/auth/admin/access/app-governance/presets/self-service-options"))) {
            MockServerWebExchange exchange = exchange(
                    requestCase.method(), requestCase.path(), "PROVIDER_ADMIN", null);

            boundary.filter(exchange, ignored -> Mono.error(
                    new AssertionError("provider auth surface must be exact-allowlisted"))).block();

            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void deniesProviderFromEveryOtherAgentDataPlaneRoute() {
        for (RequestCase requestCase : List.of(
                new RequestCase(HttpMethod.GET, "/api/agent/v1/actions"),
                new RequestCase(HttpMethod.POST, "/api/agent/v1/ask"),
                new RequestCase(HttpMethod.POST, "/api/agent/v1/question-launches"),
                new RequestCase(HttpMethod.POST, "/api/agent/v1/question-launches/consume"),
                new RequestCase(HttpMethod.GET, "/api/agent/v1/admin/overview"))) {
            MockServerWebExchange exchange = exchange(
                    requestCase.method(), requestCase.path(), "PROVIDER_ADMIN", null);

            boundary.filter(exchange, ignored -> Mono.error(
                    new AssertionError("provider Agent access must remain exact-allowlisted")))
                    .block();

            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void protectsTenantAuthorityEvaluationPathsWithoutSupportContext() {
        for (RequestCase requestCase : tenantAuthorityRequests()) {
            MockServerWebExchange exchange = exchange(
                    requestCase.method(), requestCase.path(), "PROVIDER_ADMIN", null);

            boundary.filter(exchange, ignored -> Mono.error(
                    new AssertionError("tenant authority endpoint must not be ambient"))).block();

            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void protectsTenantAuthorityEvaluationEvenWithAResolvedSupportContext() {
        for (RequestCase requestCase : tenantAuthorityRequests()) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(requestCase.method(), requestCase.path())
                    .header(VerifiedIdentityFilter.USER_HEADER, "900001")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "42")
                    .header(VerifiedIdentityFilter.ROLES_HEADER, "PROVIDER_ADMIN")
                    .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                    .header(SupportSessionContextFilter.SUPPORT_SESSION_HEADER, "session-1")
                    .header(SupportSessionContextFilter.ACTOR_TENANT_HEADER, "1")
                    .build());
            exchange.getAttributes().put("dwp.supportSessionId", "session-1");

            boundary.filter(exchange, ignored -> Mono.error(
                    new AssertionError("tenant authority APIs are not a support surface"))).block();

            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void permitsOnlySupportContextResolvedByThePrecedingFilter() {
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.just(
                new VerifiedIdentity(
                        "900001", "1", List.of("PROVIDER_ADMIN"), "PROVIDER")));
        SupportSessionContextFilter support = new SupportSessionContextFilter((request, token) ->
                Mono.just(new VerifiedSupportAccess(
                        "session-1", "provider-tenant-1", "42", "acme", "Acme",
                        List.of("TENANT_EXPERIENCE_PREVIEW"), "STANDARD",
                        Instant.now().plusSeconds(300), 1)));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-experience-preview")
                .cookie(new HttpCookie(SupportSessionContextFilter.SUPPORT_COOKIE, "secret"))
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        identity.filter(exchange, identityExchange -> support.filter(identityExchange,
                supportExchange -> boundary.filter(supportExchange, ignored -> {
                    forwarded.set(true);
                    return Mono.empty();
                }))).block();

        assertThat(forwarded).isTrue();
    }

    @Test
    void supportContextDoesNotTurnArbitraryAuthRoutesIntoSupportSurfaces() {
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.just(
                new VerifiedIdentity(
                        "900001", "1", List.of("PROVIDER_ADMIN"), "PROVIDER")));
        SupportSessionContextFilter support = new SupportSessionContextFilter((request, token) ->
                Mono.just(new VerifiedSupportAccess(
                        "session-1", "provider-tenant-1", "42", "acme", "Acme",
                        List.of("TENANT_EXPERIENCE_PREVIEW"), "STANDARD",
                        Instant.now().plusSeconds(300), 1)));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/auth/work/access-review-items/40000000-0000-0000-0000-000000000002")
                .cookie(new HttpCookie(SupportSessionContextFilter.SUPPORT_COOKIE, "secret"))
                .build());

        identity.filter(exchange, identityExchange -> support.filter(identityExchange,
                supportExchange -> boundary.filter(supportExchange, ignored -> Mono.error(
                        new AssertionError("support context cannot widen Auth access"))))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supportContextCannotWidenBeyondTheExactReadOnlyPreview() {
        for (RequestCase requestCase : List.of(
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/admin/tenant-experience-preview"),
                new RequestCase(HttpMethod.GET, "/api/platform/v1/home"),
                new RequestCase(HttpMethod.GET, "/api/people/v1/people"))) {
            MockServerWebExchange exchange = exchange(
                    requestCase.method(), requestCase.path(), "PROVIDER_ADMIN", "session-1");
            exchange.getAttributes().put("dwp.supportSessionId", "session-1");

            boundary.filter(exchange, ignored -> Mono.error(
                    new AssertionError("support access must remain preview-only"))).block();

            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void tenantASupportCredentialCannotReadTenantBServiceRowsThroughTheFilterChain() {
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.just(
                new VerifiedIdentity(
                        "900001", "1", List.of("PROVIDER_ADMIN"), "PROVIDER")));
        SupportSessionContextFilter support = new SupportSessionContextFilter((request, token) ->
                Mono.just(new VerifiedSupportAccess(
                        "tenant-a-session", "provider-tenant-a", "42", "tenant-a", "Tenant A",
                        List.of("TENANT_EXPERIENCE_PREVIEW"), "STANDARD",
                        Instant.now().plusSeconds(300), 1)));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/people/v1/people?tenantId=84")
                .cookie(new HttpCookie(SupportSessionContextFilter.SUPPORT_COOKIE, "tenant-a-token"))
                .build());
        AtomicInteger tenantBRowsRead = new AtomicInteger();

        identity.filter(exchange, identityExchange -> support.filter(identityExchange,
                supportExchange -> boundary.filter(supportExchange, ignored -> {
                    tenantBRowsRead.incrementAndGet();
                    return Mono.empty();
                }))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(tenantBRowsRead).hasValue(0);
    }

    @Test
    void spoofedSupportHeadersAreRemovedBeforeTheProviderBoundary() {
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> Mono.just(
                new VerifiedIdentity(
                        "900001", "1", List.of("PROVIDER_ADMIN"), "PROVIDER")));
        SupportSessionContextFilter support = new SupportSessionContextFilter((request, token) ->
                Mono.error(new AssertionError("no support token was presented")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-branding")
                .header(SupportSessionContextFilter.SUPPORT_SESSION_HEADER, "spoofed")
                .header(SupportSessionContextFilter.ACTOR_TENANT_HEADER, "1")
                .build());

        identity.filter(exchange, identityExchange -> support.filter(identityExchange,
                supportExchange -> boundary.filter(supportExchange, ignored -> Mono.error(
                        new AssertionError("spoofed context must not pass"))))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private MockServerWebExchange exchange(String path, String roles, String supportSessionId) {
        return exchange(HttpMethod.GET, path, roles, supportSessionId);
    }

    private MockServerWebExchange exchange(
            HttpMethod method,
            String path,
            String roles,
            String supportSessionId) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(method, path)
                .header(VerifiedIdentityFilter.USER_HEADER, "900001")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .header(VerifiedIdentityFilter.ROLES_HEADER, roles)
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER");
        if (supportSessionId != null) {
            request.header(SupportSessionContextFilter.SUPPORT_SESSION_HEADER, supportSessionId)
                    .header(SupportSessionContextFilter.ACTOR_TENANT_HEADER, "1");
        }
        return MockServerWebExchange.from(request.build());
    }

    private void assertProviderForwarded(RequestCase requestCase) {
        MockServerWebExchange exchange = exchange(
                requestCase.method(), requestCase.path(), "PROVIDER_ADMIN", null);
        AtomicBoolean forwarded = new AtomicBoolean();

        boundary.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).as(requestCase.path()).isTrue();
    }

    private List<RequestCase> tenantAuthorityRequests() {
        return List.of(
                new RequestCase(HttpMethod.GET,
                        "/api/auth/product-surface-contexts"),
                new RequestCase(HttpMethod.POST,
                        "/api/auth/product-surface-access/evaluate"),
                new RequestCase(HttpMethod.POST,
                        "/api/auth/governed-route-access/evaluate"),
                new RequestCase(HttpMethod.POST,
                        "/api/auth/product-surface-step-up-challenges"));
    }

    private Mono<Void> invoke(
            List<GlobalFilter> filters,
            int index,
            ServerWebExchange exchange) {
        if (index == filters.size()) return Mono.empty();
        return filters.get(index).filter(
                exchange,
                next -> invoke(filters, index + 1, next));
    }

    private static final class UnavailableProductAuthorityFilter
            implements GlobalFilter, Ordered {
        private final AtomicBoolean invoked;

        private UnavailableProductAuthorityFilter(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        @Override
        public Mono<Void> filter(
                ServerWebExchange exchange,
                org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
            invoked.set(true);
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }

        @Override
        public int getOrder() {
            return -94;
        }
    }

    private record RequestCase(HttpMethod method, String path) {
    }
}
