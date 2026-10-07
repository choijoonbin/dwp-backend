package com.dwp.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HrisServiceIdentityFiltersTest {

    @Test
    void payrollRouteReplacesSpoofedServiceToken() {
        PayrollServiceIdentityFilter filter = new PayrollServiceIdentityFilter("payroll-trusted");
        assertTrustedToken(filter, "/api/payroll/v1/hris/payroll/foundation/configurations",
                "payroll-trusted");
    }

    @Test
    void timeRouteReplacesSpoofedServiceToken() {
        TimeServiceIdentityFilter filter = new TimeServiceIdentityFilter("time-trusted");
        assertTrustedToken(filter, "/api/time/v1/hris/work-plans", "time-trusted");
    }

    @Test
    void hrisServiceRoutesFailClosedWithoutGatewayToken() {
        for (var fixture : java.util.List.of(
                new Fixture(new PayrollServiceIdentityFilter(""),
                        "/api/payroll/v1/hris/payroll/foundation/configurations"),
                new Fixture(new TimeServiceIdentityFilter(""),
                        "/api/time/v1/hris/work-plans"))) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(fixture.path()).build());
            fixture.filter().filter(exchange, ignored -> Mono.empty()).block();
            assertThat(exchange.getResponse().getStatusCode()).as(fixture.path())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void assertTrustedToken(
            org.springframework.cloud.gateway.filter.GlobalFilter filter,
            String path,
            String expected) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path)
                        .header("X-DWP-Service-Token", "attacker-token")
                        .build());
        AtomicReference<String> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = current -> {
            forwarded.set(current.getRequest().getHeaders()
                    .getFirst("X-DWP-Service-Token"));
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        assertThat(forwarded.get()).isEqualTo(expected);
    }

    private record Fixture(
            org.springframework.cloud.gateway.filter.GlobalFilter filter,
            String path) {
    }
}
