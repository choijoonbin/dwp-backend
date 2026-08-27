package com.dwp.gateway;

import com.dwp.gateway.filter.PlatformServiceIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformServiceIdentityFilterTest {

    @Test
    void replacesExternalServiceTokenForPlatformRoutes() {
        PlatformServiceIdentityFilter filter = new PlatformServiceIdentityFilter("trusted-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/v1/admin/reference-sets")
                        .header(PlatformServiceIdentityFilter.SERVICE_TOKEN_HEADER, "attacker-token")
                        .build());
        AtomicReference<String> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = current -> {
            forwarded.set(current.getRequest().getHeaders()
                    .getFirst(PlatformServiceIdentityFilter.SERVICE_TOKEN_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isEqualTo("trusted-token");
    }

    @Test
    void failsClosedWhenPlatformTokenIsMissing() {
        PlatformServiceIdentityFilter filter = new PlatformServiceIdentityFilter("");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/v1/reference-data/WORK_STATUS").build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void publicPlatformCatchAllCannotReachInternalServiceRoutes() {
        PlatformServiceIdentityFilter filter = new PlatformServiceIdentityFilter("trusted-token");

        for (String path : List.of(
                "/api/platform/internal",
                "/api/platform/internal/",
                "/api/platform/internal/provider",
                "/api/platform/internal/provider/v1/tenants",
                "/api/platform/internal/provider/v1/widget-registry/definitions")) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(path)
                            .header(PlatformServiceIdentityFilter.SERVICE_TOKEN_HEADER,
                                    "spoofed-service-token")
                            .header("X-DWP-Service-Identity", "dwp-gateway")
                            .header("X-DWP-Provisioning-Token", "valid-provisioning-token")
                            .build());
            AtomicBoolean forwarded = new AtomicBoolean();

            filter.filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(exchange.getResponse().getStatusCode()).as(path)
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(forwarded).as(path).isFalse();
        }
    }

    @Test
    void internalPrefixLookalikeRemainsARegularPlatformRouteCandidate() {
        PlatformServiceIdentityFilter filter =
                new PlatformServiceIdentityFilter("trusted-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/internalized/v1/catalog").build());
        AtomicReference<String> forwarded = new AtomicReference<>();

        filter.filter(exchange, current -> {
            forwarded.set(current.getRequest().getHeaders()
                    .getFirst(PlatformServiceIdentityFilter.SERVICE_TOKEN_HEADER));
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwarded.get()).isEqualTo("trusted-token");
    }
}
