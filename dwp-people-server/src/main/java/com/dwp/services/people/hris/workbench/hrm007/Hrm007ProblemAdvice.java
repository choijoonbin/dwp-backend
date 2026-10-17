package com.dwp.services.people.hris.workbench.hrm007;

import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Locale;
import java.util.UUID;

/** Closed, opaque error surface for only the HRM-007 owner controller. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = Hrm007IaEntryQueryController.class)
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm007ProblemAdvice {

    private static final String CORRELATION = "X-Correlation-ID";

    @ExceptionHandler(Hrm007Exception.class)
    ResponseEntity<Hrm007Problem> closed(
            Hrm007Exception exception,
            HttpServletRequest request) {
        return response(
                exception.status(), exception.code(), exception.retryable(), request);
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Hrm007Problem> opaqueAuthority(
            BaseException ignored,
            HttpServletRequest request) {
        return response(
                HttpStatus.FORBIDDEN, "HRIS_WORKBENCH_ACCESS_DENIED", false, request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Hrm007Problem> invalid(
            Exception ignored,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Hrm007Problem> unavailable(
            Exception ignored,
            HttpServletRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "HRIS_WORKBENCH_PROJECTION_UNAVAILABLE", true, request);
    }

    private ResponseEntity<Hrm007Problem> response(
            HttpStatus status,
            String code,
            boolean retryable,
            HttpServletRequest request) {
        UUID correlation = correlation(request);
        String title = switch (status.value()) {
            case 400 -> "Invalid request";
            case 403 -> "Access denied";
            default -> "Projection unavailable";
        };
        Hrm007Problem problem = new Hrm007Problem(
                "urn:dwp:problem:hris:hrm007:"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                title, status.value(), code, correlation, retryable);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(CORRELATION, correlation.toString())
                .body(problem);
    }

    private UUID correlation(HttpServletRequest request) {
        UUID query = uuid(request.getParameter("correlationId"));
        if (query != null) return query;
        UUID header = uuid(request.getHeader(CORRELATION));
        return header == null ? UUID.randomUUID() : header;
    }

    private UUID uuid(String value) {
        if (value == null || value.length() > 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
