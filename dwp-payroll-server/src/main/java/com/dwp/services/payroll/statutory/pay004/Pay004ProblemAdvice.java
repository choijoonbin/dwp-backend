package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = Pay004Controller.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
final class Pay004ProblemAdvice {

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Object> base(
            BaseException failure, HttpServletRequest request) {
        return response(failure.getErrorCode(), failure.getMessage(), request);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Object> invalid(
            Exception ignored, HttpServletRequest request) {
        return response(ErrorCode.INVALID_INPUT_VALUE,
                "The request does not match closed IAQ-049.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(
            Exception ignored, HttpServletRequest request) {
        return response(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The PAY-004 result is temporarily unavailable.", request);
    }

    private ResponseEntity<Object> response(
            ErrorCode error, String title, HttpServletRequest request) {
        int status = error.getHttpStatus().value();
        UUID correlation = correlation(request);
        String code = switch (status) {
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "AUTHORIZATION_DENIED";
            case 404 -> "TARGET_NOT_DISCLOSED";
            case 409 -> "STALE_OR_STATE_CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 500, 502, 503 -> "RESULT_UNKNOWN";
            default -> "SCHEMA_INVALID";
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set("X-Correlation-ID", correlation.toString());
        return ResponseEntity.status(HttpStatusCode.valueOf(status))
                .headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type", "urn:dwp:problem:" + code,
                        "title", title == null ? "PAY-004 request failed." : title,
                        "status", status,
                        "code", code,
                        "correlationId", correlation,
                        "retryable", status == 429 || status >= 500));
    }

    private UUID correlation(HttpServletRequest request) {
        String value = request.getHeader("X-Correlation-ID");
        if (value == null) value = request.getParameter("correlationId");
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            return UUID.randomUUID();
        }
    }
}
