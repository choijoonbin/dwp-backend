package com.dwp.gateway;

import com.dwp.gateway.filter.AgentServiceIdentityFilter;
import com.dwp.gateway.filter.ServiceIdentitySanitizingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceIdentityFilterTest {

    @Test
    void replacesSpoofedServiceTokenOnAgentRoutes() {
        AgentServiceIdentityFilter filter = new AgentServiceIdentityFilter("gateway-secret");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .header(AgentServiceIdentityFilter.SERVICE_TOKEN_HEADER, "spoofed")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(
                AgentServiceIdentityFilter.SERVICE_TOKEN_HEADER))
                .isEqualTo("gateway-secret");
    }

    @Test
    void failsClosedWhenAgentServiceIdentityIsNotConfigured() {
        AgentServiceIdentityFilter filter = new AgentServiceIdentityFilter(" ");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void stripsInternalServiceTokenBeforeRoutingExternalRequests() {
        ServiceIdentitySanitizingFilter filter = new ServiceIdentitySanitizingFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/auth/me")
                .header(AgentServiceIdentityFilter.SERVICE_TOKEN_HEADER, "spoofed")
                .build());
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get().getHeaders().containsKey(
                ServiceIdentitySanitizingFilter.SERVICE_TOKEN_HEADER)).isFalse();
    }
}
