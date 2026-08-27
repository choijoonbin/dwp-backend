package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.security.SupportSessionVerifier;
import com.dwp.gateway.security.VerifiedSupportAccess;
import com.dwp.gateway.security.ProviderSupportSessionVerifier.SupportValidationUnavailableException;
import com.dwp.gateway.security.ProviderSupportSessionVerifier.SupportValidationRejectedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SupportSessionContextFilterTest {

    @Test
    void replacesTheSessionTenantWithTheVerifiedSupportTargetAndStripsTheBearerCookie() {
        SupportSessionVerifier verifier = (request, token) -> Mono.just(new VerifiedSupportAccess(
                "session-1",
                "provider-tenant-1",
                "42",
                "acme",
                "Acme",
                List.of("TENANT_CONFIGURATION_READ"),
                "STANDARD",
                Instant.now().plusSeconds(600),
                0));
        SupportSessionContextFilter filter = new SupportSessionContextFilter(verifier);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-branding")
                .header(VerifiedIdentityFilter.USER_HEADER, "17")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "3")
                .header(VerifiedIdentityFilter.ROLES_HEADER, "PROVIDER_SUPPORT")
                .cookie(new HttpCookie("DWP_SESSION", "browser-session"))
                .cookie(new HttpCookie(SupportSessionContextFilter.SUPPORT_COOKIE, "support-secret"))
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER))
                .isEqualTo("42");
        assertThat(forwarded.get().getHeaders().getFirst(
                SupportSessionContextFilter.SUPPORT_SESSION_HEADER)).isEqualTo("session-1");
        assertThat(forwarded.get().getHeaders().getFirst(
                SupportSessionContextFilter.ACTOR_TENANT_HEADER)).isEqualTo("3");
        assertThat(forwarded.get().getHeaders().getFirst(
                SupportSessionContextFilter.SUPPORT_REVISION_HEADER)).isEqualTo("support-v0");
        assertThat(forwarded.get().getHeaders().getFirst("Cookie"))
                .contains("DWP_SESSION=browser-session")
                .doesNotContain("DWP_SUPPORT_SESSION");
    }

    @Test
    void failsClosedWhenTheProviderRejectsTheSupportCredential() {
        SupportSessionContextFilter filter = new SupportSessionContextFilter(
                (request, token) -> Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/people/v1/people")
                .header(VerifiedIdentityFilter.USER_HEADER, "17")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "3")
                .cookie(new HttpCookie(SupportSessionContextFilter.SUPPORT_COOKIE, "expired"))
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void convertsSupportDenialToServiceUnavailableWhenCentralEvidenceCannotBeWritten() {
        GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.generated.json"));
        SupportSessionContextFilter filter = new SupportSessionContextFilter(
                (request, token) -> Mono.empty(),
                catalog,
                (exchange, denial) -> Mono.error(new IllegalStateException("sink unavailable")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-experience-preview")
                .header(VerifiedIdentityFilter.USER_HEADER, "17")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "3")
                .cookie(new HttpCookie(
                        SupportSessionContextFilter.SUPPORT_COOKIE, "expired"))
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void failsClosedWithoutForwardingWhenProviderVerificationIsUnavailable() {
        GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.generated.json"));
        SupportSessionContextFilter filter = new SupportSessionContextFilter(
                (request, token) -> Mono.error(new SupportValidationUnavailableException()),
                catalog,
                GatewayDenialAuditSink.NOOP);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-experience-preview")
                .header(VerifiedIdentityFilter.USER_HEADER, "17")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "3")
                .cookie(new HttpCookie(
                        SupportSessionContextFilter.SUPPORT_COOKIE, "support-secret"))
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(forwarded).isFalse();
    }

    @Test
    void preservesProviderRequestRejectionsWithoutForwardingToTheTenantDataPlane() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND)) {
            GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                    new ObjectMapper(),
                    new ClassPathResource(
                            "product-authorization/product-surfaces-v1.generated.json"));
            SupportSessionContextFilter filter = new SupportSessionContextFilter(
                    (request, token) -> Mono.error(
                            new SupportValidationRejectedException(status)),
                    catalog,
                    GatewayDenialAuditSink.NOOP);
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .get("/api/platform/v1/admin/tenant-experience-preview")
                    .header(VerifiedIdentityFilter.USER_HEADER, "17")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "3")
                    .cookie(new HttpCookie(
                            SupportSessionContextFilter.SUPPORT_COOKIE, "rejected"))
                    .build());
            AtomicBoolean forwarded = new AtomicBoolean();

            filter.filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(exchange.getResponse().getStatusCode()).as(status.toString())
                    .isEqualTo(status);
            assertThat(forwarded).as(status.toString()).isFalse();
        }
    }

    @Test
    void doesNotForwardSpoofedSupportContextWithoutAResolvedSession() {
        SupportSessionContextFilter filter = new SupportSessionContextFilter(
                (request, token) -> Mono.error(new AssertionError("must not validate")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-branding")
                .header(SupportSessionContextFilter.SUPPORT_SESSION_HEADER, "spoofed")
                .header(SupportSessionContextFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_WRITE")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().containsKey(
                SupportSessionContextFilter.SUPPORT_SESSION_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                SupportSessionContextFilter.SUPPORT_SCOPES_HEADER)).isFalse();
    }

    @Test
    void doesNotResolveRetiredTenantAuthorityPathsAsSupportSurfaces() {
        AtomicReference<String> verifiedPath = new AtomicReference<>();
        SupportSessionContextFilter filter = new SupportSessionContextFilter((request, token) -> {
            verifiedPath.set(request.getURI().getPath());
            return Mono.error(new AssertionError("tenant authority APIs are not support surfaces"));
        });

        for (String path : List.of(
                "/api/auth/product-surface-contexts",
                "/api/auth/product-surface-access/evaluate",
                "/api/auth/governed-route-access/evaluate",
                "/api/auth/product-surface-step-up-challenges")) {
            MockServerHttpRequest.BaseBuilder<?> request = path.endsWith("contexts")
                    ? MockServerHttpRequest.get(path)
                    : MockServerHttpRequest.post(path);
            MockServerWebExchange exchange = MockServerWebExchange.from(request
                    .header(VerifiedIdentityFilter.USER_HEADER, "17")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "3")
                    .cookie(new HttpCookie(
                            SupportSessionContextFilter.SUPPORT_COOKIE, "support-secret"))
                    .build());
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filteredExchange -> {
                forwarded.set(filteredExchange.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get().getHeaders().containsKey(
                    SupportSessionContextFilter.SUPPORT_SESSION_HEADER)).as(path).isFalse();
            assertThat(forwarded.get().getHeaders().getFirst("Cookie")).as(path).isNull();
        }
        assertThat(verifiedPath.get()).isNull();
    }

    @Test
    void legacyExemptWorkforceAccessStillRequiresSupportSessionVerification() {
        AtomicReference<String> verifiedPath = new AtomicReference<>();
        GeneratedProductRouteCatalog catalog = new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new ClassPathResource(
                        "product-authorization/product-surfaces-v1.generated.json"));
        SupportSessionContextFilter filter = new SupportSessionContextFilter((request, token) -> {
            verifiedPath.set(request.getURI().getPath());
            return Mono.empty();
        }, catalog, GatewayDenialAuditSink.NOOP);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/people/v1/admin/workforce/access-policies")
                .cookie(new HttpCookie(SupportSessionContextFilter.SUPPORT_COOKIE, "expired"))
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(verifiedPath).hasValue(
                "/api/people/v1/admin/workforce/access-policies");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
