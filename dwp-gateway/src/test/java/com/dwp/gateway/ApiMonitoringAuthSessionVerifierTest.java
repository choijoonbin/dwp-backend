package com.dwp.gateway;

import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiMonitoringAuthSessionVerifierTest {

    @Test
    void requestsOnlyTheApiMonitoringAuthorityForTheExactPathFamily() {
        for (String path : List.of(
                "/api/platform/v1/admin/api-history",
                "/api/platform/v1/admin/api-history/overview",
                "/api/platform/v1/admin/api-history/events/7c03fd3c-58d9-47bb-8218-bc499c9a178d",
                "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.WINDOW",
                "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER",
                "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.HTTP_METHOD_FILTER",
                "/api/platform/v1/catalog/code-sets/PLATFORM.API_HISTORY.OUTCOME_FILTER")) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, true);

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get(path)
                    .build()).block();

            assertThat(captured.get().url().getQuery())
                    .as(path)
                    .isEqualTo("permissionPrefix=ADMIN.API_MONITORING");
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).containsExactly(
                    "ADMIN.API_MONITORING:MANAGE",
                    "ADMIN.API_MONITORING:VIEW");
        }
    }

    @Test
    void doesNotBroadenTheProjectionToAPathWithOnlyTheSameTextPrefix() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        AuthSessionVerifier verifier = verifier(captured, false);

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/admin/api-history-archive")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isNull();
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).isEmpty();
    }

    private AuthSessionVerifier verifier(
            AtomicReference<ClientRequest> captured,
            boolean includePermissions) {
        String permissions = includePermissions
                ? """
                  [
                    {"resourceKey":"ADMIN.API_MONITORING","permissionCode":"VIEW","effect":"ALLOW"},
                    {"resourceKey":"ADMIN.API_MONITORING","permissionCode":"MANAGE","effect":"ALLOW"}
                  ]
                  """
                : "[]";
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                            "permissions":%s}}
                            """.formatted(permissions))
                    .build());
        });
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }
}
