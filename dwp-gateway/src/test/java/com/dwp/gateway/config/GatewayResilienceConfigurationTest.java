package com.dwp.gateway.config;

import com.dwp.observability.api.ApiHistoryAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayResilienceConfigurationTest {

    private final KeyResolver resolver = new GatewayResilienceConfiguration().enterpriseKeyResolver();

    @Test
    void rateLimitsVerifiedUsersByTenantAndActor() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/platform/v1/home"));
        exchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, "71");
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, "900018");

        assertThat(resolver.resolve(exchange).block()).isEqualTo("tenant:71:actor:900018");
    }

    @Test
    void rateLimitsAnonymousTrafficByNetworkAddress() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2.17", 43120))
                .build();

        assertThat(resolver.resolve(MockServerWebExchange.from(request)).block())
                .isEqualTo("network:192.0.2.17");
    }

    @Test
    void doesNotTrustAnUnverifiedActorAttributeWithoutTenantIdentity() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/home")
                .remoteAddress(new InetSocketAddress("198.51.100.8", 53120))
                .build());
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, "spoofed");

        assertThat(resolver.resolve(exchange).block()).isEqualTo("network:198.51.100.8");
    }
}
