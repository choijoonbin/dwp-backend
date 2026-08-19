package com.dwp.services.notification.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@RestControllerAdvice
public class NotificationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationExceptionHandler.class);

    @ExceptionHandler(NotificationException.class)
    ResponseEntity<ApiResponse<Void>> notificationException(
            NotificationException exception,
            HttpServletRequest request) {
        NotificationErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.status()).body(ApiResponse.error(
                code, exception.getMessage(), correlationId(request)));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        MissingRequestHeaderException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ApiResponse<Void>> invalidInput(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                NotificationErrorCode.INVALID_INPUT,
                "The request does not satisfy the notification contract.",
                correlationId(request)));
    }

    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    void asyncClientDisconnected(Exception exception) {
        log.debug("Notification SSE client disconnected: {}", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled notification request failure", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.error(
                NotificationErrorCode.INTERNAL_ERROR,
                NotificationErrorCode.INTERNAL_ERROR.message(),
                correlationId(request)));
    }

    private String correlationId(HttpServletRequest request) {
        return request == null ? null : request.getHeader("X-Correlation-ID");
    }
}
