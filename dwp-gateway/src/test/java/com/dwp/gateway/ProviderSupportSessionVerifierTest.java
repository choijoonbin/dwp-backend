package com.dwp.gateway;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.ProviderSupportSessionVerifier;
import com.dwp.gateway.security.VerifiedSupportAccess;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSupportSessionVerifierTest {

    @Test
    void consumesTheInternalVerifiedContextWithExplicitProviderAndAuthTenantIds() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ProviderSupportSessionVerifier verifier = verifier(captured, """
                {"success":true,"data":{
                  "supportSessionId":"session-1",
                  "providerTenantId":"00000000-0000-0000-0000-000000000001",
                  "authTenantId":"42",
                  "tenantKey":"acme","tenantName":"Acme",
                  "scopes":["TENANT_EXPERIENCE_PREVIEW"],
                  "accessMode":"STANDARD","expiresAt":"2030-01-01T00:00:00Z",
                  "version":4}}
                """);
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/admin/tenant-experience-preview")
                .header(VerifiedIdentityFilter.USER_HEADER, "900001")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "1")
                .header(VerifiedIdentityFilter.IDENTITY_PLANE_HEADER, "PROVIDER")
                .build();

        VerifiedSupportAccess access = verifier.verify(request, "opaque-support-token").block();

        assertThat(access).isNotNull();
        assertThat(access.providerTenantId())
                .isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(access.authTenantId()).isEqualTo("42");
        assertThat(captured.get().headers().getFirst(
                ProviderSupportSessionVerifier.RESOURCE_PATH_HEADER))
                .isEqualTo("/api/platform/v1/admin/tenant-experience-preview");
        assertThat(captured.get().cookies().getFirst(
                ProviderSupportSessionVerifier.SUPPORT_COOKIE))
                .isEqualTo("opaque-support-token");
    }

    @Test
    void rejectsTheLegacyBrowserProjectionAsAnInternalRoutingContract() {
        ProviderSupportSessionVerifier verifier = verifier(new AtomicReference<>(), """
                {"success":true,"data":{
                  "supportSessionId":"session-1",
                  "tenantId":"00000000-0000-0000-0000-000000000001",
                  "tenantKey":"acme","tenantName":"Acme",
                  "scopes":["TENANT_EXPERIENCE_PREVIEW"],
                  "accessMode":"STANDARD","expiresAt":"2030-01-01T00:00:00Z",
                  "version":4}}
                """);

        assertThatThrownBy(() -> verifier.verify(MockServerHttpRequest
                        .get("/api/platform/v1/admin/tenant-experience-preview").build(),
                "opaque-support-token").block())
                .isInstanceOf(ProviderSupportSessionVerifier.SupportValidationUnavailableException.class);
    }

    @Test
    void mapsProvider503ToAClosedVerificationUnavailableSignal() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"success\":false}")
                        .build()));
        ProviderSupportSessionVerifier verifier = new ProviderSupportSessionVerifier(
                builder, "http://provider.test", "service-token",
                "validation-token", Duration.ofSeconds(1));

        assertThatThrownBy(() -> verifier.verify(MockServerHttpRequest
                        .get("/api/platform/v1/admin/tenant-experience-preview")
                        .build(), "opaque-support-token").block())
                .isInstanceOf(
                        ProviderSupportSessionVerifier.SupportValidationUnavailableException.class);
    }

    @Test
    void preservesProviderBadRequestAsANormalValidationRejection() {
        ProviderSupportSessionVerifier verifier = verifierWithStatus(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> verifier.verify(MockServerHttpRequest
                        .get("/api/platform/v1/admin/tenant-experience-preview")
                        .build(), "malformed-support-token").block())
                .isInstanceOfSatisfying(
                        ProviderSupportSessionVerifier.SupportValidationRejectedException.class,
                        exception -> assertThat(exception.statusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void preservesProviderNotFoundAsANormalValidationRejection() {
        ProviderSupportSessionVerifier verifier = verifierWithStatus(HttpStatus.NOT_FOUND);

        assertThatThrownBy(() -> verifier.verify(MockServerHttpRequest
                        .get("/api/platform/v1/admin/tenant-experience-preview")
                        .build(), "unknown-support-token").block())
                .isInstanceOfSatisfying(
                        ProviderSupportSessionVerifier.SupportValidationRejectedException.class,
                        exception -> assertThat(exception.statusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void keepsProviderForbiddenAsAnOpaqueSupportDenial() {
        ProviderSupportSessionVerifier verifier = verifierWithStatus(HttpStatus.FORBIDDEN);

        assertThat(verifier.verify(MockServerHttpRequest
                        .get("/api/platform/v1/admin/tenant-experience-preview")
                        .build(), "expired-support-token").block())
                .isNull();
    }

    private ProviderSupportSessionVerifier verifierWithStatus(HttpStatus status) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"success\":false}")
                        .build()));
        return new ProviderSupportSessionVerifier(
                builder, "http://provider.test", "service-token",
                "validation-token", Duration.ofSeconds(1));
    }

    private ProviderSupportSessionVerifier verifier(
            AtomicReference<ClientRequest> captured,
            String body) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });
        return new ProviderSupportSessionVerifier(
                builder, "http://provider.test", "service-token",
                "validation-token", Duration.ofSeconds(1));
    }
}
