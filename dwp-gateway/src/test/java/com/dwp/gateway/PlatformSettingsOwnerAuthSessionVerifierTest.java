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

class PlatformSettingsOwnerAuthSessionVerifierTest {

    @Test
    void requestsExactAuthorityForEveryPlatformSettingsOwnerPathFamily() {
        for (AuthorityCase authorityCase : List.of(
                new AuthorityCase(
                        "/api/platform/v1/admin/tenant-branding",
                        "ADMIN.TENANT_BRANDING"),
                new AuthorityCase(
                        "/api/platform/v1/admin/tenant-branding/history",
                        "ADMIN.TENANT_BRANDING"),
                new AuthorityCase(
                        "/api/platform/v1/admin/preference-exceptions",
                        "ADMIN.MANAGED_PREFERENCES"),
                new AuthorityCase(
                        "/api/platform/v1/admin/preference-exceptions/17",
                        "ADMIN.MANAGED_PREFERENCES"),
                new AuthorityCase(
                        "/api/platform/v1/admin/localization",
                        "ADMIN.LOCALIZATION"),
                new AuthorityCase(
                        "/api/platform/v1/admin/localization/history",
                        "ADMIN.LOCALIZATION"),
                new AuthorityCase(
                        "/api/platform/v1/admin/catalog",
                        "ADMIN.PLATFORM_CATALOG"),
                new AuthorityCase(
                        "/api/platform/v1/admin/catalog/relations",
                        "ADMIN.PLATFORM_CATALOG"),
                new AuthorityCase(
                        "/api/platform/v1/admin/registry-entries",
                        "ADMIN.PLATFORM_REGISTRY"),
                new AuthorityCase(
                        "/api/platform/v1/admin/registry-entries/17",
                        "ADMIN.PLATFORM_REGISTRY"),
                new AuthorityCase(
                        "/api/platform/v1/admin/navigation",
                        "ADMIN.NAVIGATION"),
                new AuthorityCase(
                        "/api/platform/v1/admin/navigation/studio",
                        "ADMIN.NAVIGATION"),
                new AuthorityCase(
                        "/api/platform/v1/admin/reference-sets",
                        "ADMIN.REFERENCE_DATA"),
                new AuthorityCase(
                        "/api/platform/v1/admin/reference-sets/WORK_STATUS",
                        "ADMIN.REFERENCE_DATA"))) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
                captured.set(request);
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,
                                "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                                "permissions":[
                                  {"resourceKey":"%s","permissionCode":"VIEW",
                                   "effect":"ALLOW"}
                                ]}}
                                """.formatted(authorityCase.permissionPrefix()))
                        .build());
            });
            AuthSessionVerifier verifier = new AuthSessionVerifier(
                    builder, "http://auth.test", Duration.ofSeconds(1));

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

    private record AuthorityCase(String path, String permissionPrefix) {}
}
