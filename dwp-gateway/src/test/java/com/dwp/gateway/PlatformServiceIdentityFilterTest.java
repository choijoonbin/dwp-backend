package com.dwp.gateway;

import com.dwp.gateway.filter.PlatformServiceIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

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
}
