package com.dwp.gateway;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.SessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HomeV2GatewaySecurityTest {

    private static final String HOME_V2 =
            "/api/platform/v2/home?deviceClass=DESKTOP_STANDARD";

    @Test
    void replacesEverySpoofableHomeAuthorityHeaderWithVerifiedIdentity() {
        SessionVerifier verifier = ignored -> Mono.just(new VerifiedIdentity(
                "7", "1", List.of("WORKSPACE_MEMBER"),
                List.of("APP.CALENDAR:VIEW", "APP.MEETINGS:VIEW"),
                List.of("58fa4516-dc70-4785-ac9f-3606992c3f6b"),
                List.of("MEETING_HOST@MEETING:42"), null, "Home user", false,
                "40000000-0000-0000-0000-000000000001", "TENANT"));
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(verifier);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get(HOME_V2)
                .header(VerifiedIdentityFilter.USER_HEADER, "900001")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "999")
                .header(VerifiedIdentityFilter.ROLES_HEADER, "PROVIDER_ADMIN")
                .header(VerifiedIdentityFilter.PERMISSIONS_HEADER, "APP.HCM:VIEW_TEAM")
                .header(VerifiedIdentityFilter.GROUP_REFS_HEADER, "spoofed-group")
                .header(VerifiedIdentityFilter.RESOURCE_ROLES_HEADER,
                        "APP_OWNER@APP.HCM")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .header(VerifiedIdentityFilter.CONTROL_PLANE_HEADER,
                        "WIDGET_REGISTRY_PROVIDER")
                .header(VerifiedIdentityFilter.WIDGET_OWNER_SCOPE_HEADER, "hcm")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER))
                .isEqualTo("7");
        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER))
                .isEqualTo("1");
        assertThat(forwarded.get().getHeaders().getFirst(VerifiedIdentityFilter.ROLES_HEADER))
                .isEqualTo("WORKSPACE_MEMBER");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.PERMISSIONS_HEADER))
                .isEqualTo("APP.CALENDAR:VIEW,APP.MEETINGS:VIEW");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.GROUP_REFS_HEADER))
                .isEqualTo("58fa4516-dc70-4785-ac9f-3606992c3f6b");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.RESOURCE_ROLES_HEADER))
                .isEqualTo("MEETING_HOST@MEETING:42");
        assertThat(forwarded.get().getHeaders().getFirst(
                VerifiedIdentityFilter.IDENTITY_PLANE_HEADER)).isEqualTo("TENANT");
        assertThat(forwarded.get().getHeaders()).doesNotContainKeys(
                VerifiedIdentityFilter.CONTROL_PLANE_HEADER,
                VerifiedIdentityFilter.WIDGET_OWNER_SCOPE_HEADER);
    }

    @Test
    void keepsHomeV2SessionProtected() {
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(ignored -> Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(HOME_V2).build());

        filter.filter(exchange, ignored -> Mono.error(
                new AssertionError("unauthenticated Home v2 must not be forwarded"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestsAllAppAuthoritiesFreshForEveryHomeV2Read() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["WORKSPACE_MEMBER"],
                            "permissions":[
                              {"resourceKey":"APP.CALENDAR","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.MEETINGS","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.HCM","permissionCode":"VIEW_TEAM","effect":"DENY"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));
        MockServerHttpRequest request = MockServerHttpRequest.get(HOME_V2)
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        VerifiedIdentity first = verifier.verify(request).block();
        VerifiedIdentity second = verifier.verify(request).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=APP.");
        assertThat(first).isNotNull();
        assertThat(first.permissions()).containsExactly(
                "APP.CALENDAR:VIEW", "APP.MEETINGS:VIEW");
        assertThat(second).isNotNull();
        assertThat(calls).hasValue(2);
    }
}
