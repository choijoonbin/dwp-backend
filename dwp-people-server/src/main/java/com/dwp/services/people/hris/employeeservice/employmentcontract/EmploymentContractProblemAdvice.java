package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011WorkloadController;
import com.dwp.services.people.hris.workbench.hrm011.Hrm011IaEntryQueryController;
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

/** Opaque HRM-011 RFC-7807-shaped error boundary with stale-version detail only. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        EmploymentContractController.class,
        Hrm011IaEntryQueryController.class,
        Hrm011WorkloadController.class
})
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class EmploymentContractProblemAdvice {

    @ExceptionHandler(EmploymentContractException.class)
    ResponseEntity<Problem> closed(
            EmploymentContractException exception, HttpServletRequest request) {
        return response(HttpStatus.valueOf(exception.status()), exception.code(),
                exception.retryable(), exception.currentVersion(), request);
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Problem> governed(
            BaseException exception, HttpServletRequest request) {
        HttpStatus status = exception.getErrorCode().getHttpStatus();
        return response(status, exception.getErrorCode().getCode(),
                status == HttpStatus.SERVICE_UNAVAILABLE, null, request);
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
    ResponseEntity<Problem> invalid(Exception ignored, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                false, null, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unavailable(Exception ignored, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "EMPLOYMENT_CONTRACT_SERVICE_UNAVAILABLE", true, null, request);
    }

    private ResponseEntity<Problem> response(
            HttpStatus status, String code, boolean retryable, Long currentVersion,
            HttpServletRequest request) {
        UUID correlation = correlation(request);
        Problem problem = new Problem(
                "urn:dwp:problem:hris:hrm011:"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                title(status), status.value(), code, correlation,
                retryable, currentVersion);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Correlation-ID", correlation.toString())
                .body(problem);
    }

    private String title(HttpStatus status) {
        return switch (status.value()) {
            case 400 -> "Invalid request";
            case 401 -> "Authentication required";
            case 403 -> "Access denied";
            case 404 -> "Resource unavailable";
            case 409 -> "Request conflict";
            case 429 -> "Request rate limited";
            default -> "Employment-contract service unavailable";
        };
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
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable,
            Long currentVersion) {
    }
}
