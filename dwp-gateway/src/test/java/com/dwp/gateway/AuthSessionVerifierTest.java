package com.dwp.gateway;

import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionVerifierTest {

    @Test
    void derivesTenantFromSessionWhenClientAssertionIsAbsent() {
        AuthSessionVerifier verifier = verifierReturningTenant("1");
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/home-experience/background")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        VerifiedIdentity identity = verifier.verify(request).block();

        assertThat(identity).isNotNull();
        assertThat(identity.tenantId()).isEqualTo("1");
    }

    @Test
    void rejectsClientTenantAssertionThatDoesNotMatchSession() {
        AuthSessionVerifier verifier = verifierReturningTenant("1");
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/reference-data/WORK_STATUS")
                .header("X-Tenant-ID", "2")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        assertThat(verifier.verify(request).block()).isNull();
    }

    @Test
    void propagatesTraceContextToSessionVerification() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":true,\"data\":{\"userId\":7,\"tenantId\":1,\"roles\":[]}}")
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));
        String traceParent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/admin/api-history/overview")
                .header("traceparent", traceParent)
                .build();

        verifier.verify(request).block();

        assertThat(captured.get().headers().getFirst("traceparent")).isEqualTo(traceParent);
    }

    private AuthSessionVerifier verifierReturningTenant(String tenantId) {
        String body = """
                {"success":true,"data":{"userId":7,"tenantId":%s,"roles":["EMPLOYEE"]}}
                """.formatted(tenantId);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()));
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }
}
