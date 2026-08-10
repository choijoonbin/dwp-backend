package com.dwp.gateway;

import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

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
