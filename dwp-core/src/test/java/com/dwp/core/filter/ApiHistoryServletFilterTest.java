package com.dwp.core.filter;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.ApiHistoryEvent;
import com.dwp.observability.api.ApiHistoryPublisher;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiHistoryServletFilterTest {

    @Test
    void capturesMetadataAndExcludesSecretsAndPayloads() throws Exception {
        List<ApiHistoryEvent> events = new ArrayList<>();
        ApiHistoryPublisher publisher = events::add;
        ApiHistoryServletFilter filter = new ApiHistoryServletFilter(
                publisher,
                "privacy-secret",
                "dwp-test-service",
                "1.0.0",
                "instance-1",
                "test");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/users/550e8400-e29b-41d4-a716-446655440000");
        request.setQueryString("password=must-not-be-recorded");
        request.setContent("private-body".getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("Authorization", "Bearer secret-token");
        request.addHeader("Cookie", "DWP_SESSION=secret-session");
        request.addHeader("User-Agent", "SensitiveBrowser/99 private");
        request.setAttribute(ApiHistoryAttributes.TENANT_ID, "7");
        request.setAttribute(ApiHistoryAttributes.ACTOR_ID, "42");
        request.setAttribute(ApiHistoryAttributes.ACTOR_TYPE, "USER");
        request.setAttribute(ApiHistoryAttributes.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        request.setAttribute(ApiHistoryAttributes.SPAN_ID, "00f067aa0ba902b7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            servletRequest.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/users/{userId}");
            ((HttpServletResponse) servletResponse).setStatus(201);
            servletResponse.getWriter().write("created");
        });

        assertThat(events).hasSize(1);
        ApiHistoryEvent event = events.get(0);
        assertThat(event.tenantId()).isEqualTo(7L);
        assertThat(event.actorId()).isEqualTo("42");
        assertThat(event.routeTemplate()).isEqualTo("/v1/users/{userId}");
        assertThat(event.requestPath()).isEqualTo("/v1/users/{id}");
        assertThat(event.statusCode()).isEqualTo(201);
        assertThat(event.responseSizeBytes()).isEqualTo(7L);
        assertThat(event.clientAddressHash()).hasSize(64).doesNotContain("192.0.2.10");
        assertThat(event.userAgentHash()).hasSize(64).doesNotContain("SensitiveBrowser");
        assertThat(event.toString())
                .doesNotContain("must-not-be-recorded")
                .doesNotContain("private-body")
                .doesNotContain("secret-token")
                .doesNotContain("secret-session");
    }

    @Test
    void neverRecordsTheInternalCollectorCall() throws Exception {
        List<ApiHistoryEvent> events = new ArrayList<>();
        ApiHistoryServletFilter filter = new ApiHistoryServletFilter(
                events::add, "secret", "platform", "1", "local", "test");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", ApiHistoryServletFilter.COLLECTOR_PATH);

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> { });

        assertThat(events).isEmpty();
    }

    @Test
    void recordsUnhandledFailuresAsServerErrors() {
        List<ApiHistoryEvent> events = new ArrayList<>();
        ApiHistoryServletFilter filter = new ApiHistoryServletFilter(
                events::add, "secret", "platform", "1", "local", "test");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/failure");

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> filter.doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (ignoredRequest, ignoredResponse) -> {
                            throw new RuntimeException("failure");
                        }));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).statusCode()).isEqualTo(500);
        assertThat(events.get(0).outcome()).isEqualTo("SERVER_ERROR");
    }
}
