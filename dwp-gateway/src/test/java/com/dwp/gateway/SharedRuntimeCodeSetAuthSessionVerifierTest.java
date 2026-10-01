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

class SharedRuntimeCodeSetAuthSessionVerifierTest {

    @Test
    void requestsAuditViewOnlyForTheExactAuditRuntimeCodeSets() {
        for (String codeSet : List.of(
                "PLATFORM.AUDIT.WINDOW",
                "PLATFORM.AUDIT.CATEGORY_FILTER",
                "PLATFORM.AUDIT.SEVERITY_FILTER",
                "PLATFORM.AUDIT.OUTCOME_FILTER",
                "PLATFORM.EVENT_ENVELOPE.DOMAIN",
                "PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
                "PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT")) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, "ADMIN.AUDIT_VIEW");

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get("/api/platform/v1/catalog/code-sets/" + codeSet)
                    .build()).block();

            assertThat(captured.get().url().getQuery())
                    .as(codeSet)
                    .isEqualTo("permissionPrefix=ADMIN.AUDIT_VIEW");
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).containsExactly("ADMIN.AUDIT_VIEW:VIEW");
        }
    }

    @Test
    void keepsPersonalPreferenceCodeSetsOnTheAuthenticatedPersonalSessionBoundary() {
        for (String codeSet : List.of(
                "PLATFORM.PREFERENCE.COLOR_MODE",
                "PLATFORM.PREFERENCE.DENSITY",
                "PLATFORM.PREFERENCE.TIME_ZONE",
                "PLATFORM.PREFERENCE.DATE_FORMAT",
                "PLATFORM.PREFERENCE.TIME_FORMAT",
                "PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
                "PLATFORM.PREFERENCE.NUMBER_FORMAT")) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, null);

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get("/api/platform/v1/catalog/code-sets/" + codeSet)
                    .build()).block();

            assertThat(captured.get().url().getQuery()).as(codeSet).isNull();
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).isEmpty();
        }
    }

    @Test
    void doesNotProjectAuditAuthorityForUnknownCatalogKeys() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        AuthSessionVerifier verifier = verifier(captured, null);

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/catalog/code-sets/PLATFORM.AUDIT.UNKNOWN")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isNull();
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).isEmpty();
    }

    private AuthSessionVerifier verifier(
            AtomicReference<ClientRequest> captured,
            String permissionPrefix) {
        String permissions = permissionPrefix == null
                ? "[]"
                : """
                  [{"resourceKey":"%s","permissionCode":"VIEW","effect":"ALLOW"}]
                  """.formatted(permissionPrefix);
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
