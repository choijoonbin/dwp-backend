package com.dwp.services.time.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Creates identical Time problem bodies for MVC advice and pre-controller filters. */
@Component
public final class HrisTimeProblemWriter {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String RETRY_AFTER_SECONDS = "1";

    private final ObjectMapper objectMapper;

    public HrisTimeProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<HrisTimeProblemV1> response(
            HttpServletRequest request, HrisTimeApiException failure) {
        UUID correlationId = correlationId(request);
        HrisTimeProblemV1 problem = problem(correlationId, failure);
        return ResponseEntity.status(failure.code().status())
                .headers(headers(correlationId, failure.code()))
                .body(problem);
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HrisTimeApiException failure) throws IOException {
        UUID correlationId = correlationId(request);
        HrisTimeProblemV1 problem = problem(correlationId, failure);
        response.setStatus(failure.code().status().value());
        HttpHeaders headers = headers(correlationId, failure.code());
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    HrisTimeProblemV1 problem(
            UUID correlationId, HrisTimeApiException failure) {
        HrisTimeProblemCode code = failure.code();
        return new HrisTimeProblemV1(
                code.type(),
                code.title(),
                code.status().value(),
                code.name(),
                correlationId,
                code.retryable(),
                failure.currentVersion(),
                failure.receiptId(),
                failure.fieldErrors());
    }

    public UUID correlationId(HttpServletRequest request) {
        if (request != null) {
            List<String> values = java.util.Collections.list(
                    request.getHeaders(CORRELATION_HEADER));
            if (values.size() == 1) {
                String value = values.getFirst();
                try {
                    UUID parsed = UUID.fromString(value);
                    if (value.equals(parsed.toString()) && !parsed.equals(new UUID(0, 0))) {
                        return parsed;
                    }
                } catch (RuntimeException ignored) {
                    // Invalid caller correlation is replaced with owner-generated evidence.
                }
            }
        }
        return UUID.randomUUID();
    }

    private HttpHeaders headers(UUID correlationId, HrisTimeProblemCode code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.set(CORRELATION_HEADER, correlationId.toString());
        if (code == HrisTimeProblemCode.RATE_LIMITED
                || code == HrisTimeProblemCode.AUTHORITY_UNAVAILABLE) {
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return headers;
    }
}
