package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import jakarta.servlet.http.HttpServletRequest;
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

/** Closed problem boundary for the HRM-008 owner routes. */
@RestControllerAdvice(assignableTypes = EmploymentEventController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class EmploymentEventAdvice {

    private static final Set<Integer> ALLOWED =
            Set.of(400, 401, 403, 404, 409, 422, 423, 429, 500, 503);

    @ExceptionHandler(EmploymentEventProblem.class)
    ResponseEntity<EmploymentEventDtos.Problem> employmentFailure(
            EmploymentEventProblem failure,
            HttpServletRequest request) {
        return response(failure.status().value(), failure.code(), failure.getMessage(),
                failure.retryable(), failure.currentVersion(), failure.receiptId(),
                correlation(request));
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<EmploymentEventDtos.Problem> baseFailure(
            BaseException failure,
            HttpServletRequest request) {
        int candidate = failure.getErrorCode() == ErrorCode.INVALID_STATE
                ? 422 : failure.getErrorCode().getHttpStatus().value();
        int status = ALLOWED.contains(candidate) ? candidate : 500;
        return response(status, failure.getErrorCode().getCode(),
                failure.getErrorCode().getMessage(), retryable(status), null, null,
                correlation(request));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<EmploymentEventDtos.Problem> invalidRequest(
            Exception ignored,
            HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "The request is invalid.", false,
                null, null, correlation(request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<EmploymentEventDtos.Problem> unexpectedFailure(
            Exception ignored,
            HttpServletRequest request) {
        return response(500, "RESULT_UNKNOWN", "The command result is unknown.", true,
                null, null, correlation(request));
    }

    private ResponseEntity<EmploymentEventDtos.Problem> response(
            int status,
            String code,
            String detail,
            boolean retryable,
            Long currentVersion,
            UUID receiptId,
            UUID correlationId) {
        EmploymentEventDtos.Problem body = new EmploymentEventDtos.Problem(
                "urn:dwp:problem:" + code, detail, status, code, correlationId,
                retryable, detail, currentVersion, receiptId);
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .headers(headers(correlationId, currentVersion))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private HttpHeaders headers(UUID correlationId, Long currentVersion) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(EmploymentEventController.CORRELATION, correlationId.toString());
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        String revision = evidence == null || evidence.decisionRevision() == null
                || evidence.decisionRevision().isBlank()
                ? "unavailable" : evidence.decisionRevision();
        headers.set(EmploymentEventController.AUTHORIZATION_REVISION, revision);
        headers.setETag("\"v:" + (currentVersion == null ? 0L : currentVersion) + "\"");
        return headers;
    }

    private boolean retryable(int status) {
        return status == 429 || status == 500 || status == 503;
    }

    private UUID correlation(HttpServletRequest request) {
        try {
            return UUID.fromString(request.getHeader(EmploymentEventController.CORRELATION));
        } catch (RuntimeException ignored) {
            return UUID.randomUUID();
        }
    }
}
