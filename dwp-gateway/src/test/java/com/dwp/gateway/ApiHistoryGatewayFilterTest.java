package com.dwp.gateway;

import com.dwp.gateway.filter.ApiHistoryGatewayFilter;
import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.ApiHistoryEvent;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class ApiHistoryGatewayFilterTest {

    @Test
    void recordsTheGatewayHopWithoutQueryOrCredentials() {
        List<ApiHistoryEvent> events = new ArrayList<>();
        ApiHistoryGatewayFilter filter = new ApiHistoryGatewayFilter(
                events::add,
                "privacy-secret",
                "dwp-gateway",
                "1.0.0",
                "gateway-1",
                "test");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/platform/v1/users/123?token=must-not-be-recorded")
                .header("Authorization", "Bearer secret-token")
                .header("User-Agent", "SensitiveBrowser/99 private")
                .build());
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()
                .id("platform-server")
                .uri(URI.create("http://localhost:8002"))
                .predicate(ignored -> true)
                .build());
        exchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, "7");
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, "42");
        exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_TYPE, "USER");
        exchange.getAttributes().put(ApiHistoryAttributes.TRACE_ID,
                "4bf92f3577b34da6a3ce929d0e0e4736");
        exchange.getAttributes().put(ApiHistoryAttributes.SPAN_ID, "00f067aa0ba902b7");

        filter.filter(exchange, observed -> {
            observed.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }).block();

        assertThat(events).hasSize(1);
        ApiHistoryEvent event = events.get(0);
        assertThat(event.observationPoint()).isEqualTo("GATEWAY");
        assertThat(event.routeId()).isEqualTo("platform-server");
        assertThat(event.requestPath()).isEqualTo("/api/platform/v1/users/{id}");
        assertThat(event.tenantId()).isEqualTo(7L);
        assertThat(event.toString())
                .doesNotContain("must-not-be-recorded")
                .doesNotContain("secret-token")
                .doesNotContain("SensitiveBrowser");
    }

    @Test
    void recordsClientCancellationAsCancelled() {
        List<ApiHistoryEvent> events = new ArrayList<>();
        ApiHistoryGatewayFilter filter = new ApiHistoryGatewayFilter(
                events::add,
                "privacy-secret",
                "dwp-gateway",
                "1.0.0",
                "gateway-1",
                "test");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/v1/slow-operation").build());

        Disposable subscription = filter.filter(exchange, ignored -> Mono.never()).subscribe();
        subscription.dispose();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).statusCode()).isEqualTo(499);
        assertThat(events.get(0).outcome()).isEqualTo("CANCELLED");
    }
}
