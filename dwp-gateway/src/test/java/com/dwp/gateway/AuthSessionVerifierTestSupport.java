package com.dwp.gateway;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.AuthSessionVerifier;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class AuthSessionVerifierTestSupport {

    protected final void assertOwnerAuthorityPrefixes(
            List<String> paths,
            String expectedPrefix,
            String ownerResource) {
        for (String path : paths) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
                captured.set(request);
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,
                                "identityPlane":"TENANT","roles":["HR_ADMIN"],
                                "permissions":[
                                  {"resourceKey":"APP.HCM","permissionCode":"VIEW","effect":"ALLOW"},
                                  {"resourceKey":"%s","permissionCode":"VIEW","effect":"ALLOW"}
                                ]}}
                                """.formatted(ownerResource))
                        .build());
            });
            AuthSessionVerifier verifier = new AuthSessionVerifier(
                    builder, "http://auth.test", Duration.ofSeconds(1));
            VerifiedIdentityFilter filter = new VerifiedIdentityFilter(verifier);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(
                    MockServerWebExchange.from(MockServerHttpRequest.get(path).build()),
                    exchange -> {
                        forwarded.set(exchange.getRequest());
                        return Mono.empty();
                    }).block();

            assertThat(captured.get().url().getQuery())
                    .isEqualTo("permissionPrefix=" + expectedPrefix);
            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    VerifiedIdentityFilter.PERMISSIONS_HEADER))
                    .isEqualTo("APP.HCM:VIEW," + ownerResource + ":VIEW");
        }
    }

    protected final void assertNoAuthorityPrefix(String path) {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["HR_ADMIN"],
                            "permissions":[]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        assertThat(verifier.verify(MockServerHttpRequest.get(path).build()).block())
                .isNotNull();
        assertThat(captured.get().url().getQuery()).isNull();
    }

    protected final AuthSessionVerifier verifierReturningTenant(String tenantId) {
        String body = """
                {"success":true,"data":{"userId":7,"tenantId":%s,"identityPlane":"TENANT","roles":["EMPLOYEE"]}}
                """.formatted(tenantId);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()));
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }

    protected final AuthSessionVerifier verifierReturningBody(String body) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()));
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }

    protected final void assertProviderResourceRolesFailClosed(String resourceRole) {
        AuthSessionVerifier verifier = verifierReturningBody("""
                {"success":true,"data":{"userId":900001,"tenantId":1,
                "identityPlane":"PROVIDER","roles":[],"resourceRoles":[%s]}}
                """.formatted(resourceRole));

        assertThatThrownBy(() -> verifier.verify(MockServerHttpRequest
                .get("/api/agent/v1/plans/preview")
                .build()).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid durable identity contract");
    }

    protected final AuthSessionVerifier verifierCountingSuccessfulCalls(AtomicInteger calls) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":true,\"data\":{\"userId\":7,\"tenantId\":1,\"identityPlane\":\"TENANT\",\"roles\":[]}}")
                    .build());
        });
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }
}
