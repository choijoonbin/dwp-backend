package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PerformanceAssignedReviewController.class)
final class PerformanceReviewExceptionHandler {

    private static final String CORRELATION = "X-Correlation-ID";

    @ExceptionHandler(BaseException.class)
    ResponseEntity<PerformanceReviewProblemDetails> base(
            BaseException exception,
            HttpServletRequest request) {
        ErrorCode error = exception.getErrorCode();
        boolean authorityUnavailable = error == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
        return response(
                authorityUnavailable ? HttpStatus.INTERNAL_SERVER_ERROR : error.getHttpStatus(),
                error.getCode(), error.getMessage(), exception.getMessage(),
                authorityUnavailable, null, null, null, request);
    }

    @ExceptionHandler(PerformanceReviewProblemException.class)
    ResponseEntity<PerformanceReviewProblemDetails> review(
            PerformanceReviewProblemException exception,
            HttpServletRequest request) {
        return response(
                exception.status(), exception.code(), exception.status().getReasonPhrase(),
                exception.getMessage(), exception.retryable(), exception.currentVersion(),
                exception.receiptId(), null, request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<PerformanceReviewProblemDetails> invalid(
            Exception exception,
            HttpServletRequest request) {
        List<PerformanceReviewProblemDetails.FieldError> fields =
                exception instanceof MethodArgumentNotValidException validation
                        ? validation.getBindingResult().getFieldErrors().stream()
                        .map(error -> new PerformanceReviewProblemDetails.FieldError(
                                error.getField(), "INVALID"))
                        .toList()
                        : null;
        return response(
                HttpStatus.BAD_REQUEST, ErrorCode.INVALID_INPUT_VALUE.getCode(),
                ErrorCode.INVALID_INPUT_VALUE.getMessage(),
                "The assigned-review request is invalid.", false,
                null, null, fields, request);
    }

    private ResponseEntity<PerformanceReviewProblemDetails> response(
            HttpStatus status,
            String code,
            String title,
            String detail,
            boolean retryable,
            Long currentVersion,
            UUID receiptId,
            List<PerformanceReviewProblemDetails.FieldError> fields,
            HttpServletRequest request) {
        PerformanceReviewProblemDetails problem = new PerformanceReviewProblemDetails(
                "/problems/hris/performance-review/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                title, status.value(), code, correlation(request), retryable,
                currentVersion, receiptId, fields, detail);
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .body(problem);
    }

    private UUID correlation(HttpServletRequest request) {
        String value = request.getHeader(CORRELATION);
        if (value != null && value.length() <= 36) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // A malformed diagnostic header is never used as trusted evidence.
            }
        }
        return UUID.randomUUID();
    }
}
