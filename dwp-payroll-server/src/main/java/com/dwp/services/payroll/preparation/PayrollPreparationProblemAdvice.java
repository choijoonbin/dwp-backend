package com.dwp.services.payroll.preparation;

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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

@RestControllerAdvice(assignableTypes = PayrollPreparationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollPreparationProblemAdvice {

    @ExceptionHandler(BaseException.class)
    ResponseEntity<Object> baseFailure(
            BaseException failure, HttpServletRequest request) {
        return response(
                request, failure.getErrorCode(), failure.getMessage());
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<Object> invalidRequest(
            Exception ignored, HttpServletRequest request) {
        return response(request, ErrorCode.INVALID_INPUT_VALUE,
                "The request does not match the closed Payroll contract.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(
            Exception ignored, HttpServletRequest request) {
        return response(request, ErrorCode.INTERNAL_SERVER_ERROR,
                "The Payroll result is temporarily unavailable.");
    }

    private ResponseEntity<Object> response(
            HttpServletRequest request, ErrorCode error, String title) {
        UUID correlationId = correlation(request);
        PayrollPreparationProblems.Definition definition =
                PayrollPreparationProblems.define(
                        query(request), error, title, correlationId);
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(PayrollPreparationController.CORRELATION,
                correlationId.toString());
        return ResponseEntity.status(HttpStatusCode.valueOf(definition.status()))
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(definition.body());
    }

    private boolean query(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().endsWith("/v1/workbench/population");
    }

    private UUID correlation(HttpServletRequest request) {
        String value = request.getHeader(PayrollPreparationController.CORRELATION);
        if (value == null || value.isBlank()) {
            value = request.getParameter("correlationId");
        }
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return UUID.randomUUID();
        }
    }
}
