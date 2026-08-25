package com.dwp.gateway;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.SessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
                true));
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
    }
}
