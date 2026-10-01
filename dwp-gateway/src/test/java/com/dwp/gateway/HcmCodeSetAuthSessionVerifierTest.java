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

class HcmCodeSetAuthSessionVerifierTest {

    @Test
    void requestsTheExistingHcmAuthorityForEachExactGovernedCodeSet() {
        List<AuthorityCase> cases = List.of(
                new AuthorityCase(
                        "PEOPLE.HRIS_SOURCE_TYPE", "ACTION.WORKFORCE_DATA_OPERATIONS"),
                new AuthorityCase(
                        "PEOPLE.HRIS_CONNECTOR_TYPE", "ACTION.WORKFORCE_DATA_OPERATIONS"),
                new AuthorityCase(
                        "PEOPLE.HRIS_AUTH_MODE", "ACTION.WORKFORCE_DATA_OPERATIONS"),
                new AuthorityCase(
                        "PEOPLE.POSITION_TYPE", "ACTION.WORKFORCE_ORG_DESIGN"),
                new AuthorityCase(
                        "PEOPLE.POSITION_CRITICALITY", "ACTION.WORKFORCE_ORG_DESIGN"));

        for (AuthorityCase authorityCase : cases) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, authorityCase.permissionPrefix());

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get("/api/platform/v1/catalog/code-sets/" + authorityCase.codeSet())
                    .build()).block();

            assertThat(captured.get().url().getQuery())
                    .as(authorityCase.codeSet())
                    .isEqualTo("permissionPrefix=" + authorityCase.permissionPrefix());
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).containsExactly(
                    authorityCase.permissionPrefix() + ":VIEW");
        }
    }

    @Test
    void doesNotWidenHcmProjectionToCommunicationsSharedOrUnknownCodeSets() {
        List<AuthorityCase> cases = List.of(
                new AuthorityCase(
                        "PLATFORM.COMMUNICATION.CATEGORY", "ADMIN.COMMUNICATIONS"),
                new AuthorityCase(
                        "PLATFORM.AUDIT.WINDOW", "ADMIN.AUDIT_VIEW"),
                new AuthorityCase(
                        "PLATFORM.API_HISTORY.WINDOW", "ADMIN.API_MONITORING"),
                new AuthorityCase("PLATFORM.PREFERENCE.COLOR_MODE", null),
                new AuthorityCase("PEOPLE.HRIS_SOURCE_TYPE.EXTRA", null),
                new AuthorityCase("PEOPLE.POSITION_UNKNOWN", null));

        for (AuthorityCase authorityCase : cases) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            AuthSessionVerifier verifier = verifier(captured, authorityCase.permissionPrefix());

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                    .get("/api/platform/v1/catalog/code-sets/" + authorityCase.codeSet())
                    .build()).block();

            if (authorityCase.permissionPrefix() == null) {
                assertThat(captured.get().url().getQuery()).as(authorityCase.codeSet()).isNull();
                assertThat(identity.permissions()).isEmpty();
            } else {
                assertThat(captured.get().url().getQuery())
                        .as(authorityCase.codeSet())
                        .isEqualTo("permissionPrefix=" + authorityCase.permissionPrefix());
                assertThat(identity.permissions()).containsExactly(
                        authorityCase.permissionPrefix() + ":VIEW");
            }
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

    private record AuthorityCase(String codeSet, String permissionPrefix) {}
}
