package com.dwp.gateway;

import com.dwp.gateway.filter.MeetingServiceIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingServiceIdentityFilterTest {

    @Test
    void replacesClientControlledTokenForMeetingRequests() {
        var filter = new MeetingServiceIdentityFilter("trusted-meeting-token");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/meetings/v1/home")
                .header(MeetingServiceIdentityFilter.SERVICE_TOKEN_HEADER, "attacker"));
        AtomicReference<String> forwarded = new AtomicReference<>();

        filter.filter(exchange, next -> {
            forwarded.set(next.getRequest().getHeaders()
                    .getFirst(MeetingServiceIdentityFilter.SERVICE_TOKEN_HEADER));
            return Mono.empty();
        }).block();

        assertThat(forwarded).hasValue("trusted-meeting-token");
    }

    @Test
    void failsClosedWhenMeetingIdentityIsMissing() {
        var filter = new MeetingServiceIdentityFilter("");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/meetings/v1/home"));

        filter.filter(exchange, ignored -> Mono.error(new AssertionError("must not forward")))
                .block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void ignoresUnrelatedRoutes() {
        var filter = new MeetingServiceIdentityFilter("trusted-meeting-token");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/messaging/v1/home"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter.filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).hasValue(true);
    }
}
