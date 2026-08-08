package com.dwp.gateway;

import com.dwp.gateway.filter.CsrfProtectionFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfProtectionFilterTest {

    private final CsrfProtectionFilter filter = new CsrfProtectionFilter();

    @Test
    void requiresMatchingDoubleSubmitTokenForDomainMutations() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .cookie(new org.springframework.http.HttpCookie("XSRF-TOKEN", "token-1"))
                .header("X-XSRF-TOKEN", "token-1")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isTrue();
    }

    @Test
    void rejectsMissingTokenForDomainMutations() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .build());

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void leavesAuthMutationsToTheAuthServiceCsrfFilter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/login")
                .build());
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isTrue();
    }
}
