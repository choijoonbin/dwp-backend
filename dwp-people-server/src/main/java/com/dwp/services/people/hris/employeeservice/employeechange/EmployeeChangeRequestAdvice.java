package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Set;
import java.util.UUID;

/** Exact local problem boundary for the HRM-010 browser-facing owner routes. */
@RestControllerAdvice(assignableTypes = EmployeeChangeRequestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeRequestAdvice {

    private static final String CORRELATION = "X-Correlation-ID";
    private static final String AUTHORIZATION_REVISION =
            "X-Authorization-Decision-Revision";
    private static final Set<Integer> IAQ_STATUSES =
            Set.of(400, 401, 403, 404, 409, 429, 503);
    private static final Set<Integer> COMMAND_STATUSES =
            Set.of(400, 401, 403, 404, 409, 422, 423, 429, 500);

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Object> baseFailure(
            BaseException failure,
            HttpServletRequest request) {
        boolean iaq = isIaq(request);
        int status = status(failure.getErrorCode(), iaq);
        String code = failure.getErrorCode().getCode();
        return response(
                iaq, status, code, failure.getErrorCode().getMessage(),
                retryable(status), correlation(request));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Object> invalidRequest(
            Exception ignored,
            HttpServletRequest request) {
        return response(
                isIaq(request), 400, "INVALID_REQUEST",
                "The request is invalid.", false, correlation(request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedFailure(
            Exception ignored,
            HttpServletRequest request) {
        boolean iaq = isIaq(request);
        int status = iaq ? 503 : 500;
        return response(
                iaq, status, iaq ? "PROJECTION_UNAVAILABLE" : "RESULT_UNKNOWN",
                iaq ? "The projection is temporarily unavailable."
                        : "The command result is unknown.",
                true, correlation(request));
    }

    private ResponseEntity<Object> response(
            boolean iaq,
            int status,
            String code,
            String title,
            boolean retryable,
            UUID correlationId) {
        Object body = iaq
                ? new EmployeeChangeDtos.Iaq009ProblemDetailsV1(
                        type(code), title, status, code, correlationId, retryable)
                : new EmployeeChangeDtos.HrisProblemDetailsV1(
                        type(code), title, status, code, correlationId, retryable,
                        null, null, null, null);
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .headers(headers(correlationId))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private HttpHeaders headers(UUID correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(CORRELATION, correlationId.toString());
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        String revision = evidence == null || evidence.decisionRevision() == null
                || evidence.decisionRevision().isBlank()
                ? "unavailable" : evidence.decisionRevision();
        headers.set(AUTHORIZATION_REVISION, revision);
        headers.setETag("\"v:0\"");
        return headers;
    }

    private int status(ErrorCode code, boolean iaq) {
        int candidate = code == ErrorCode.INVALID_STATE
                ? 422 : code.getHttpStatus().value();
        Set<Integer> allowed = iaq ? IAQ_STATUSES : COMMAND_STATUSES;
        if (allowed.contains(candidate)) return candidate;
        return iaq ? 503 : 500;
    }

    private boolean retryable(int status) {
        return status == 429 || status == 500 || status == 503;
    }

    private UUID correlation(HttpServletRequest request) {
        String value = request.getHeader(CORRELATION);
        if (value == null || value.isBlank()) value = request.getParameter("correlationId");
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return UUID.randomUUID();
        }
    }

    private boolean isIaq(HttpServletRequest request) {
        return request.getRequestURI().contains("/workbench/");
    }

    private String type(String code) {
        return "urn:dwp:problem:" + code;
    }
}
