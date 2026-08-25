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

    @Test
    void appliesGatewayCsrfToProductAndGovernedEvaluations() {
        for (String path : new String[] {
                "/api/auth/product-surface-access/evaluate",
                "/api/auth/governed-route-access/evaluate",
                "/api/auth/product-surface-step-up-challenges"
        }) {
            MockServerWebExchange rejected = MockServerWebExchange.from(
                    MockServerHttpRequest.post(path).build());

            filter.filter(rejected, ignored -> Mono.empty()).block();

            assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            AtomicBoolean forwarded = new AtomicBoolean();
            MockServerWebExchange accepted = MockServerWebExchange.from(
                    MockServerHttpRequest.post(path)
                            .cookie(new org.springframework.http.HttpCookie(
                                    "XSRF-TOKEN", "token-product"))
                            .header("X-XSRF-TOKEN", "token-product")
                            .build());

            filter.filter(accepted, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(forwarded).isTrue();
        }
    }

    @Test
    void treatsTheContextListAsASafeMethod() {
        AtomicBoolean forwarded = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/product-surface-contexts").build());

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isTrue();
    }
}
