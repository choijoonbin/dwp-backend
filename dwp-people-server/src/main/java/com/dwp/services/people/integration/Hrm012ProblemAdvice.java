package com.dwp.services.people.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.workbench.hrm012.Hrm012IaEntryQueryController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        Hrm012WorkforceIngestionController.class,
        Hrm012IaEntryQueryController.class
})
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class Hrm012ProblemAdvice {

    @ExceptionHandler(Hrm012Problem.class)
    ResponseEntity<Problem> problem(Hrm012Problem error, HttpServletRequest request) {
        return response(error.status(), error.code(), error.retryable(), request);
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Problem> governed(BaseException error, HttpServletRequest request) {
        int status = error.getErrorCode().getHttpStatus().value();
        return response(status, error.getErrorCode().getCode(), status == 503, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Problem> invalid(Exception ignored, HttpServletRequest request) {
        return response(400, "HRM012_INVALID_REQUEST", false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unavailable(Exception ignored, HttpServletRequest request) {
        return response(503, "HRM012_SERVICE_UNAVAILABLE", true, request);
    }

    private ResponseEntity<Problem> response(
            int status, String code, boolean retryable, HttpServletRequest request) {
        UUID correlation = correlation(request);
        Problem body = new Problem(
                "urn:dwp:problem:hris:hrm012:"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                status == 400 ? "Invalid request" : status == 403
                        ? "Access denied" : status == 409
                        ? "Request conflict" : "HRM-012 service unavailable",
                status, code, correlation, retryable);
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Correlation-ID", correlation.toString())
                .body(body);
    }

    private UUID correlation(HttpServletRequest request) {
        for (String value : new String[]{
                request.getParameter("correlationId"),
                request.getHeader("X-Correlation-ID")}) {
            try {
                if (value != null) return UUID.fromString(value);
            } catch (RuntimeException ignored) {
                // Generate an opaque support correlation below.
            }
        }
        return UUID.randomUUID();
    }

    record Problem(
            String type, String title, int status, String code,
            UUID correlationId, boolean retryable) {
    }
}
