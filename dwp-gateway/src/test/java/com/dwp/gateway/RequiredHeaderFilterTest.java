package com.dwp.gateway;

import com.dwp.gateway.filter.RequiredHeaderFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredHeaderFilterTest {

    @Test
    void rejectsProtectedRequestsWithoutTenantHeader() {
        RequiredHeaderFilter filter = new RequiredHeaderFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/protected").build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void allowsPublicLoginRequests() {
        RequiredHeaderFilter filter = new RequiredHeaderFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());
        AtomicBoolean called = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            called.set(true);
            return Mono.empty();
        }).block();

        assertThat(called).isTrue();
    }
}
