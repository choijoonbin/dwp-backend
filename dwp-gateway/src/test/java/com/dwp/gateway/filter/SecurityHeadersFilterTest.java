package com.dwp.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter("");

    @Test
    void addsBrowserSecurityHeadersToSuccessfulResponses() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/platform/v1/home"));

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertSecurityHeaders(exchange);
    }

    @Test
    void addsBrowserSecurityHeadersEvenWhenTheDownstreamFails() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/platform/v1/home"));

        filter.filter(exchange, ignored -> Mono.error(new IllegalStateException("upstream failed")))
                .onErrorResume(ignored -> Mono.empty())
                .block();

        assertSecurityHeaders(exchange);
    }

    @Test
    void allowsOnlyTheConfiguredLiveKitWssOriginInConnectSources() {
        SecurityHeadersFilter configuredFilter = new SecurityHeadersFilter(
                "wss://meet.example.com:7443/rtc?access_token=must-not-leak#fragment");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/meetings/v1/meetings/meeting-1/token"));

        configuredFilter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .contains("connect-src 'self' wss://meet.example.com:7443;")
                .doesNotContain("access_token", "must-not-leak", "#fragment");
    }

    @Test
    void rejectsNonWssLiveKitSources() {
        SecurityHeadersFilter configuredFilter = new SecurityHeadersFilter(
                "https://meet.example.com/rtc");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/meetings/v1/meetings/meeting-1/token"));

        configuredFilter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .contains("connect-src 'self'; object-src 'none'")
                .doesNotContain("https://meet.example.com");
    }

    private void assertSecurityHeaders(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .contains("default-src 'self'", "frame-ancestors 'none'");
        assertThat(exchange.getResponse().getHeaders().getFirst("Permissions-Policy"))
                .contains("camera=(self)", "microphone=(self)", "display-capture=(self)")
                .doesNotContain("camera=()", "microphone=()", "display-capture=()");
        assertThat(exchange.getResponse().getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(exchange.getResponse().getHeaders().getFirst("Cross-Origin-Opener-Policy"))
                .isEqualTo("same-origin");
        assertThat(exchange.getResponse().getHeaders().getFirst("Cross-Origin-Resource-Policy"))
                .isEqualTo("same-origin");
    }
}
