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

class CommunicationsCodeSetAuthSessionVerifierTest {

    @Test
    void requestsCommunicationsAuthorityForOnlyTheExactGovernedCodeSets() {
        for (String codeSet : List.of(
                "PLATFORM.COMMUNICATION.CATEGORY",
                "PLATFORM.COMMUNICATION.CONTENT_TYPE")) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, "ADMIN.COMMUNICATIONS");

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get("/api/platform/v1/catalog/code-sets/" + codeSet)
                    .build()).block();

            assertThat(captured.get().url().getQuery())
                    .as(codeSet)
                    .isEqualTo("permissionPrefix=ADMIN.COMMUNICATIONS");
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).containsExactly("ADMIN.COMMUNICATIONS:VIEW");
        }
    }

    @Test
    void doesNotProjectCommunicationsAuthorityForUnknownCodeSets() {
        for (String codeSet : List.of(
                "PLATFORM.COMMUNICATION.UNKNOWN",
                "PLATFORM.COMMUNICATION.CATEGORY.EXTRA")) {
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
