package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Locale;
import java.util.UUID;

/** Opaque RFC-7807-shaped failures for only the HRM-009 owner controller. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CertificateController.class)
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class CertificateProblemAdvice {

    private static final String CORRELATION = "X-Correlation-ID";

    @ExceptionHandler(CertificateException.class)
    ResponseEntity<CertificateDtos.Problem> closed(
            CertificateException exception,
            HttpServletRequest request) {
        return response(
                exception.status(), exception.code(), exception.retryable(), request);
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<CertificateDtos.Problem> governed(
            BaseException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.getErrorCode().getHttpStatus();
        return response(status, exception.getErrorCode().getCode(),
                status == HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<CertificateDtos.Problem> invalid(
            Exception ignored,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<CertificateDtos.Problem> unavailable(
            Exception ignored,
            HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "CERTIFICATE_SERVICE_UNAVAILABLE", true, request);
    }

    private ResponseEntity<CertificateDtos.Problem> response(
            HttpStatus status,
            String code,
            boolean retryable,
            HttpServletRequest request) {
        UUID correlationId = correlation(request);
        String title = switch (status.value()) {
            case 400 -> "Invalid request";
            case 401 -> "Authentication required";
            case 403 -> "Access denied";
            case 404 -> "Resource unavailable";
            case 409 -> "Request conflict";
            case 429 -> "Request rate limited";
            default -> "Certificate service unavailable";
        };
        CertificateDtos.Problem problem = new CertificateDtos.Problem(
                "urn:dwp:problem:hris:hrm009:"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                title, status.value(), code, correlationId, retryable);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(CORRELATION, correlationId.toString())
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
