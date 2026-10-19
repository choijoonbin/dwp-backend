package com.dwp.services.payroll.statutory.pay003;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Pay003ProblemAdviceTest {

    @Test
    void unexpectedFailureUsesClosed503ProblemAndKeepsCorrelation() {
        UUID correlation = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", correlation.toString());

        var response = new Pay003ProblemAdvice().unexpected(
                new IllegalStateException("private failure details"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
                .isEqualTo(correlation.toString());
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody()).isEqualTo(Map.of(
                "type", "urn:dwp:problem:RESULT_UNKNOWN",
                "title", "The PAY-003 result is temporarily unavailable.",
                "status", 503,
                "code", "RESULT_UNKNOWN",
                "correlationId", correlation,
                "retryable", true));
    }
}
