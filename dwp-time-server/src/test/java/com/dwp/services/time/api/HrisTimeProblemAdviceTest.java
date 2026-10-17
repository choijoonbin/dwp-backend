package com.dwp.services.time.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class HrisTimeProblemAdviceTest {

    private static final UUID CORRELATION =
            UUID.fromString("17900000-0000-4000-8000-000000000021");

    private HrisTimeProblemAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new HrisTimeProblemAdvice(
                new HrisTimeProblemWriter(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void authorityUnavailableHasSixRequiredFieldsAndRetryHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HrisTimeProblemWriter.CORRELATION_HEADER, CORRELATION.toString());

        ResponseEntity<HrisTimeProblemV1> response = advice.hris(
                HrisTimeApiException.of(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
                .isEqualTo(CORRELATION.toString());
        assertThat(response.getBody()).isNotNull().satisfies(problem -> {
            assertThat(problem.type())
                    .isEqualTo("urn:dwp:hris-time:problem:authority-unavailable");
            assertThat(problem.title()).isEqualTo("Service Unavailable");
            assertThat(problem.status()).isEqualTo(503);
            assertThat(problem.code()).isEqualTo("AUTHORITY_UNAVAILABLE");
            assertThat(problem.correlationId()).isEqualTo(CORRELATION);
            assertThat(problem.retryable()).isTrue();
            assertThat(problem.currentVersion()).isNull();
            assertThat(problem.receiptId()).isNull();
            assertThat(problem.fieldErrors()).isNull();
        });
    }

    @Test
    void legacyNotFoundBecomesTheConstantOpaqueTargetProblem() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HrisTimeProblemWriter.CORRELATION_HEADER, CORRELATION.toString());

        ResponseEntity<HrisTimeProblemV1> response = advice.legacy(
                new BaseException(ErrorCode.NOT_FOUND, "sensitive target detail"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().containsKey("Retry-After")).isFalse();
        assertThat(response.getBody()).isNotNull().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("TARGET_NOT_DISCLOSED");
            assertThat(problem.title()).isEqualTo("Not Found");
        });
    }

    @Test
    void malformedOrDuplicateCorrelationIsReplacedAndNormalized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HrisTimeProblemWriter.CORRELATION_HEADER, "NOT-A-UUID");
        request.addHeader(HrisTimeProblemWriter.CORRELATION_HEADER, CORRELATION.toString());

        ResponseEntity<HrisTimeProblemV1> response = advice.invalid(
                new IllegalArgumentException("invalid"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        UUID generated = response.getBody().correlationId();
        assertThat(generated).isNotEqualTo(CORRELATION);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
                .isEqualTo(generated.toString());
    }
}
