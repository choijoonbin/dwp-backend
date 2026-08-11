package com.dwp.gateway;

import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.SupportSessionVerifier;
import com.dwp.gateway.security.VerifiedSupportAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
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
}
