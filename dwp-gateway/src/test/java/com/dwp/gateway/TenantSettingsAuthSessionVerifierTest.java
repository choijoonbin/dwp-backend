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

class TenantSettingsAuthSessionVerifierTest {

    @Test
    void requestsEachExactTenantProjectionAuthorityFromAuth() {
        for (AuthorityCase authorityCase : List.of(
                new AuthorityCase(
                        "/api/provider/v1/tenant/settings/provider-domains",
                        "ADMIN.IDENTITY_PROVISIONING"),
                new AuthorityCase(
                        "/api/provider/v1/tenant/settings/data-governance-observation",
                        "ADMIN.AUDIT_VIEW"),
                new AuthorityCase(
                        "/api/provider/v1/tenant/settings/plan-eligibility",
                        "ADMIN.APP_GOVERNANCE"))) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, authorityCase.permissionPrefix());

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get(authorityCase.path())
                    .build()).block();

            assertThat(captured.get().url().getQuery())
                    .as(authorityCase.path())
                    .isEqualTo("permissionPrefix=" + authorityCase.permissionPrefix());
            assertThat(identity).isNotNull();
            assertThat(identity.permissions())
                    .containsExactly(authorityCase.permissionPrefix() + ":VIEW");
        }
    }

    @Test
    void doesNotBroadenTheMappingToUnknownTenantSettingsPaths() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                            "permissions":[]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/provider/v1/tenant/settings/future-projection")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isNull();
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).isEmpty();
    }

    private AuthSessionVerifier verifier(
            AtomicReference<ClientRequest> captured,
            String permissionPrefix) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                            "permissions":[{"resourceKey":"%s","permissionCode":"VIEW",
                            "effect":"ALLOW"}]}}
                            """.formatted(permissionPrefix))
                    .build());
        });
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }

    private record AuthorityCase(String path, String permissionPrefix) {}
}
