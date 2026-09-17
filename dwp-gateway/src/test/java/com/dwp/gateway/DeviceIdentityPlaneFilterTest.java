package com.dwp.gateway;

import com.dwp.gateway.filter.CsrfProtectionFilter;
import com.dwp.gateway.filter.DeviceIdentityPlaneFilter;
import com.dwp.gateway.filter.PlatformServiceIdentityFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.observability.api.ApiHistoryAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceIdentityPlaneFilterTest {
    private static final String CREDENTIAL =
            "opaque-device-credential-material-1234567890";

    @Test
    void createsDeviceEvidenceForEveryGovernedDeviceRoute() {
        DeviceIdentityPlaneFilter filter = new DeviceIdentityPlaneFilter();
        String id = "40000000-0000-0000-0000-000000000017";
        List<RequestCase> routes = List.of(
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/device/workplace/devices:register"),
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/device/workplace/devices/" + id + "/heartbeat"),
                new RequestCase(HttpMethod.GET,
                        "/api/platform/v1/device/workplace/devices/" + id + "/projection"),
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/device/workplace/devices/" + id
                                + "/access-pass:pair"),
                new RequestCase(HttpMethod.GET, "/api/platform/v1/workplace/kiosk/session"),
                new RequestCase(HttpMethod.GET,
                        "/api/platform/v1/workplace/kiosk/visits/" + id),
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/workplace/kiosk/visits/" + id + ":arrive"),
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/workplace/kiosk/visits/" + id + ":checkout"),
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/workplace/kiosk/devices/" + id + ":heartbeat"),
                new RequestCase(HttpMethod.POST,
                        "/api/platform/v1/workplace/kiosk/devices/" + id + ":help"));

        for (RequestCase route : routes) {
            MockServerWebExchange exchange = exchange(route.method(), route.path(), "00042",
                    CREDENTIAL);
            AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();

            filter.filter(exchange, next -> {
                forwarded.set(next.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(route.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    DeviceIdentityPlaneFilter.INTERNAL_TENANT_HEADER)).isEqualTo("42");
            assertThat(forwarded.get().getHeaders().getFirst(
                    DeviceIdentityPlaneFilter.INTERNAL_PLANE_HEADER)).isEqualTo("DEVICE");
            assertThat(forwarded.get().getHeaders().getFirst(
                    DeviceIdentityPlaneFilter.INTERNAL_CREDENTIAL_HEADER)).isEqualTo(CREDENTIAL);
            assertThat(forwarded.get().getHeaders().containsKey(
                    DeviceIdentityPlaneFilter.PUBLIC_CREDENTIAL_HEADER)).isFalse();
        }
    }

    @Test
    void removesForgedTrustedHeadersBeforeRegeneratingMinimalEvidence() {
        DeviceIdentityPlaneFilter filter = new DeviceIdentityPlaneFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/workplace/kiosk/session")
                .header(DeviceIdentityPlaneFilter.PUBLIC_TENANT_HEADER, "42")
                .header(DeviceIdentityPlaneFilter.PUBLIC_CREDENTIAL_HEADER, CREDENTIAL)
                .header("X-DWP-Tenant-ID", "999")
                .header("X-DWP-User-ID", "7")
                .header("X-DWP-Roles", "PLATFORM_ADMIN")
                .header("X-DWP-Permissions", "ADMIN.WORKPLACE:MANAGE")
                .header("X-DWP-Support-Session-ID", "forged")
                .header("X-DWP-Device-Identity-SHA256", "a".repeat(64))
                .header("X-DWP-Service-Token", "forged")
                .build());
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();

        filter.filter(exchange, next -> {
            forwarded.set(next.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst("X-DWP-Tenant-ID")).isEqualTo("42");
        assertThat(forwarded.get().getHeaders().getFirst("X-DWP-Device-Credential"))
                .isEqualTo(CREDENTIAL);
        assertThat(forwarded.get().getHeaders()).doesNotContainKeys(
                "X-DWP-User-ID", "X-DWP-Roles", "X-DWP-Permissions",
                "X-DWP-Support-Session-ID", "X-DWP-Device-Identity-SHA256",
                "X-DWP-Service-Token");
        assertThat(exchange.getAttribute(ApiHistoryAttributes.ACTOR_ID).toString())
                .startsWith("device:").doesNotContain(CREDENTIAL);
    }

    @Test
    void failsClosedForMissingDuplicateOrMalformedPublicEvidence() {
        DeviceIdentityPlaneFilter filter = new DeviceIdentityPlaneFilter();
        List<MockServerWebExchange> invalid = List.of(
                exchange(HttpMethod.GET, "/api/platform/v1/workplace/kiosk/session",
                        null, CREDENTIAL),
                exchange(HttpMethod.GET, "/api/platform/v1/workplace/kiosk/session",
                        "42", null),
                exchange(HttpMethod.GET, "/api/platform/v1/workplace/kiosk/session",
                        "tenant-42", CREDENTIAL),
                exchange(HttpMethod.GET, "/api/platform/v1/workplace/kiosk/session",
                        "42", "short"),
                MockServerWebExchange.from(MockServerHttpRequest
                        .get("/api/platform/v1/workplace/kiosk/session")
                        .header(DeviceIdentityPlaneFilter.PUBLIC_TENANT_HEADER, "42", "43")
                        .header(DeviceIdentityPlaneFilter.PUBLIC_CREDENTIAL_HEADER, CREDENTIAL)
                        .build()),
                MockServerWebExchange.from(MockServerHttpRequest
                        .get("/api/platform/v1/workplace/kiosk/session")
                        .header(DeviceIdentityPlaneFilter.PUBLIC_TENANT_HEADER, "42")
                        .header(DeviceIdentityPlaneFilter.PUBLIC_CREDENTIAL_HEADER,
                                CREDENTIAL, CREDENTIAL + "-other")
                        .build()),
                MockServerWebExchange.from(MockServerHttpRequest
                        .get("/api/platform/v1/workplace/kiosk/session")
                        .header(DeviceIdentityPlaneFilter.PUBLIC_TENANT_HEADER, "42")
                        .header("X-DWP-Device-Identity-SHA256", "a".repeat(64))
                        .build()));

        for (MockServerWebExchange exchange : invalid) {
            AtomicInteger forwarded = new AtomicInteger();
            filter.filter(exchange, ignored -> {
                forwarded.incrementAndGet();
                return Mono.empty();
            }).block();
            assertThat(forwarded).hasValue(0);
            assertThat(exchange.getResponse().getStatusCode())
                    .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void bypassesBrowserSessionAndCsrfOnlyAfterDeviceEvidenceIsVerified() {
        DeviceIdentityPlaneFilter device = new DeviceIdentityPlaneFilter();
        CsrfProtectionFilter csrf = new CsrfProtectionFilter();
        AtomicInteger sessionVerifications = new AtomicInteger();
        VerifiedIdentityFilter identity = new VerifiedIdentityFilter(ignored -> {
            sessionVerifications.incrementAndGet();
            return Mono.empty();
        });
        PlatformServiceIdentityFilter platform = new PlatformServiceIdentityFilter("trusted");
        MockServerWebExchange exchange = exchange(HttpMethod.POST,
                "/api/platform/v1/device/workplace/devices:register", "42", CREDENTIAL);
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();

        device.filter(exchange, deviceExchange -> csrf.filter(deviceExchange,
                csrfExchange -> identity.filter(csrfExchange,
                        identityExchange -> platform.filter(identityExchange, finalExchange -> {
                            forwarded.set(finalExchange.getRequest());
                            return Mono.empty();
                        })))).block();

        assertThat(sessionVerifications).hasValue(0);
        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst("X-DWP-Service-Token"))
                .isEqualTo("trusted");
        assertThat(forwarded.get().getHeaders().getFirst("X-DWP-Identity-Plane"))
                .isEqualTo("DEVICE");
    }

    private static MockServerWebExchange exchange(
            HttpMethod method, String path, String tenant, String credential) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, path);
        if (tenant != null) builder.header(DeviceIdentityPlaneFilter.PUBLIC_TENANT_HEADER, tenant);
        if (credential != null) builder.header(
                DeviceIdentityPlaneFilter.PUBLIC_CREDENTIAL_HEADER, credential);
        return MockServerWebExchange.from(builder.build());
    }

    private record RequestCase(HttpMethod method, String path) { }
}
