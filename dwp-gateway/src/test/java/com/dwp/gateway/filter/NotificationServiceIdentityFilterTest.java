package com.dwp.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceIdentityFilterTest {

    @Test
    void replacesClientTokenForNotificationRequests() {
        var filter = new NotificationServiceIdentityFilter("trusted-notification-token");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/notifications/v1/summary")
                .header("X-DWP-Service-Token", "client-controlled")
                .header("X-DWP-Source-Service", "dwp-approval-server")
                .header("X-DWP-Caller-Service", "forged-client"));
        AtomicReference<String> forwardedToken = new AtomicReference<>();
        AtomicReference<String> forwardedCaller = new AtomicReference<>();
        AtomicReference<String> forwardedSource = new AtomicReference<>();

        filter.filter(exchange, next -> {
            forwardedToken.set(next.getRequest().getHeaders().getFirst("X-DWP-Service-Token"));
            forwardedCaller.set(next.getRequest().getHeaders().getFirst("X-DWP-Caller-Service"));
            forwardedSource.set(next.getRequest().getHeaders().getFirst("X-DWP-Source-Service"));
            return Mono.empty();
        }).block();

        assertThat(forwardedToken).hasValue("trusted-notification-token");
        assertThat(forwardedCaller).hasValue("dwp-gateway");
        assertThat(forwardedSource).hasValue("dwp-gateway");
    }

    @Test
    void failsClosedWhenServiceIdentityIsMissing() {
        var filter = new NotificationServiceIdentityFilter("");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/notifications/v1/summary"));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not forward")))
                .block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void ignoresUnrelatedRoutes() {
        var filter = new NotificationServiceIdentityFilter("trusted-notification-token");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/platform/v1/home"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).hasValue(true);
    }
}
