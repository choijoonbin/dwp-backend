package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.SessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedIdentityFilterTest {

    @Test
    void replacesSpoofedIdentityHeadersWithVerifiedValues() {
        SessionVerifier verifier = ignored -> Mono.just(new VerifiedIdentity(
                "user-7",
                "tenant-1",
                List.of("EMPLOYEE", "APPROVER"),
                List.of("ADMIN.AUDIT_VIEW:VIEW"),
                List.of("58fa4516-dc70-4785-ac9f-3606992c3f6b"),
                List.of("APP_ACCESS_APPROVER@APP.MAIL"),
                null,
                "김민서",
                true,
                "40000000-0000-0000-0000-000000000001",
                "TENANT"));
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(verifier);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/agent/v1/plans/preview")
                .header("X-Tenant-ID", "tenant-1")
                .header(VerifiedIdentityFilter.USER_HEADER, "spoofed")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "spoofed")
                .header(VerifiedIdentityFilter.PERMISSIONS_HEADER, "SPOOFED:MANAGE")
                .header(VerifiedIdentityFilter.GROUP_REFS_HEADER, "spoofed-team")
                .header(VerifiedIdentityFilter.RESOURCE_ROLES_HEADER,
                        "APP_OWNER@APP.SPOOFED")
                .header(VerifiedIdentityFilter.DISPLAY_NAME_HEADER, "c3Bvb2ZlZA")
                .header(VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER,
                        "spoofed-session-family")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .header(VerifiedIdentityFilter.LEGACY_ROLE_FALLBACK_HEADER, "false")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER))
                .isEqualTo("user-7");
        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER))
                .isEqualTo("tenant-1");
        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.ROLES_HEADER))
                .isEqualTo("EMPLOYEE,APPROVER");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.PERMISSIONS_HEADER))
                .isEqualTo("ADMIN.AUDIT_VIEW:VIEW");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.GROUP_REFS_HEADER))
                .isEqualTo("58fa4516-dc70-4785-ac9f-3606992c3f6b");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.RESOURCE_ROLES_HEADER))
                .isEqualTo("APP_ACCESS_APPROVER@APP.MAIL");
        assertThat(new String(Base64.getUrlDecoder().decode(
                forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.DISPLAY_NAME_HEADER)),
                StandardCharsets.UTF_8)).isEqualTo("김민서");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.LEGACY_ROLE_FALLBACK_HEADER)).isEqualTo("true");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER))
                .isEqualTo("40000000-0000-0000-0000-000000000001");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.IDENTITY_PLANE_HEADER)).isEqualTo("TENANT");
    }

    @Test
    void rejectsMissingOrInvalidSessions() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored -> Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/agent/v1/plans/preview")
                .header("X-Tenant-ID", "tenant-1")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void keepsHomeBackgroundSessionProtected() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored -> Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/home-experience/background?v=3")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void keepsTenantLogoSessionProtected() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored -> Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/tenant-branding/logo?v=1")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void failsClosedWhenTheAuthServiceIsUnavailable() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored ->
                Mono.error(new IllegalStateException("auth unavailable")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/agent/v1/plans/preview")
                .header("X-Tenant-ID", "tenant-1")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void keepsAuthRoutesPublicAndStripsInternalHeaders() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored ->
                Mono.error(new AssertionError("verifier must not be called")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/auth/policy")
                .header(VerifiedIdentityFilter.USER_HEADER, "spoofed")
                .header(VerifiedIdentityFilter.PERMISSIONS_HEADER, "SPOOFED:MANAGE")
                .header(VerifiedIdentityFilter.GROUP_REFS_HEADER, "spoofed-team")
                .header(VerifiedIdentityFilter.RESOURCE_ROLES_HEADER,
                        "APP_OWNER@APP.SPOOFED")
                .header(VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER, "spoofed")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().containsKey(VerifiedIdentityFilter.USER_HEADER))
                .isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.PERMISSIONS_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.GROUP_REFS_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.RESOURCE_ROLES_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.IDENTITY_PLANE_HEADER)).isFalse();
    }

    @Test
    void verifiesEveryProtectedAuthSurfaceBeforeForwardingIt() {
        List<RequestCase> requests = List.of(
                new RequestCase(HttpMethod.GET, "/api/auth/me"),
                new RequestCase(HttpMethod.GET, "/api/auth/sessions"),
                new RequestCase(HttpMethod.PATCH, "/api/auth/me/locale"),
                new RequestCase(HttpMethod.DELETE,
                        "/api/auth/sessions/40000000-0000-0000-0000-000000000001"),
                new RequestCase(HttpMethod.GET,
                        "/api/auth/work/access-review-items/40000000-0000-0000-0000-000000000002"),
                new RequestCase(HttpMethod.GET,
                        "/api/auth/admin/access/app-governance/presets/self-service-options"));
        AtomicInteger verificationCount = new AtomicInteger();
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored -> {
            verificationCount.incrementAndGet();
            return Mono.just(new VerifiedIdentity(
                    "900001", "1", List.of("PROVIDER_ADMIN"), List.of(),
                    List.of(), List.of(), null, "Provider operator", false,
                    "40000000-0000-0000-0000-000000000003", "PROVIDER"));
        });

        for (RequestCase requestCase : requests) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(requestCase.method(), requestCase.path())
                    .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "TENANT")
                    .build());
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filteredExchange -> {
                forwarded.set(filteredExchange.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    VerifiedIdentityFilter.IDENTITY_PLANE_HEADER))
                    .as(requestCase.path()).isEqualTo("PROVIDER");
        }
        assertThat(verificationCount.get()).isEqualTo(requests.size());
    }

    @Test
    void letsAuthValidateSessionRefreshWithoutCallingMeFirst() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored ->
                Mono.error(new AssertionError("refresh must be validated by Auth directly")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/session/refresh")
                .header(VerifiedIdentityFilter.USER_HEADER, "spoofed")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.USER_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.IDENTITY_PLANE_HEADER)).isFalse();
    }

    @Test
    void doesNotTreatWrongMethodOrUnknownAuthRoutesAsPublic() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored -> Mono.empty());

        for (RequestCase requestCase : List.of(
                new RequestCase(HttpMethod.POST, "/api/auth/policy"),
                new RequestCase(HttpMethod.GET, "/api/auth/idp"),
                new RequestCase(HttpMethod.GET, "/api/auth/logout"),
                new RequestCase(HttpMethod.GET, "/api/auth/session/refresh"),
                new RequestCase(HttpMethod.GET, "/api/auth/future-account-feature"),
                new RequestCase(HttpMethod.GET, "/api/auth/activations/token/extra"))) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(requestCase.method(), requestCase.path())
                    .build());

            filter.filter(exchange, ignored -> Mono.error(
                    new AssertionError("protected request must not be forwarded"))).block();

            assertThat(exchange.getResponse().getStatusCode()).as(requestCase.path())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void verifiesTheGatewayOwnedAuthContractsAndStepUpIssuer() {
        for (String path : List.of(
                "/api/auth/product-surface-contexts",
                "/api/auth/product-surface-access/evaluate",
                "/api/auth/governed-route-access/evaluate",
                "/api/auth/product-surface-step-up-challenges")) {
            VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored ->
                    Mono.just(new VerifiedIdentity(
                            "7", "1", List.of("WORKSPACE_MEMBER"), "TENANT")));
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .method(path.endsWith("contexts") ? HttpMethod.GET : HttpMethod.POST,
                            path)
                    .build());
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filteredExchange -> {
                forwarded.set(filteredExchange.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER))
                    .isEqualTo("7");
            assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER))
                    .isEqualTo("1");
        }
    }

    @Test
    void auditsTenantAssertionMismatchBeforeCommittingForbidden() {
        AtomicReference<GatewayDenialAuditSink.Denial> evidence = new AtomicReference<>();
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(
                ignored -> Mono.just(new VerifiedIdentity(
                        "7", "1", List.of("WORKSPACE_MEMBER"), "TENANT")),
                (exchange, denial) -> {
                    evidence.set(denial);
                    return Mono.empty();
                });
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/product-surface-step-up-challenges")
                .header("X-Tenant-ID", "2")
                .build());

        filter.filter(exchange, ignored -> Mono.error(
                new AssertionError("tenant mismatch must not be forwarded"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(evidence.get().policyId()).isEqualTo("TENANT_ASSERTION_BOUNDARY_V1");
    }

    @Test
    void returnsServiceUnavailableWhenTenantMismatchEvidenceCannotBeWritten() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(
                ignored -> Mono.just(new VerifiedIdentity(
                        "7", "1", List.of("WORKSPACE_MEMBER"), "TENANT")),
                (exchange, denial) -> Mono.error(new IllegalStateException("sink unavailable")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/product-surface-step-up-challenges")
                .header("X-Tenant-ID", "2")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private record RequestCase(HttpMethod method, String path) {
    }
}
