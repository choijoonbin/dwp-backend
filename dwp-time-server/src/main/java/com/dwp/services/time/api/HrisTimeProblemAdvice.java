package com.dwp.services.time.api;

import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Problem boundary scoped only to central command receipts and the future Time close package. */
@RestControllerAdvice(basePackages = {
        "com.dwp.services.time.commandreceipt",
        "com.dwp.services.time.close"
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HrisTimeProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(HrisTimeProblemAdvice.class);

    private final HrisTimeProblemWriter writer;

    public HrisTimeProblemAdvice(HrisTimeProblemWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler(HrisTimeApiException.class)
    ResponseEntity<HrisTimeProblemV1> hris(
            HrisTimeApiException failure, HttpServletRequest request) {
        return writer.response(request, failure);
    }

    @ExceptionHandler(BaseException.class)
    ResponseEntity<HrisTimeProblemV1> legacy(
            BaseException failure, HttpServletRequest request) {
        return writer.response(request, HrisTimeApiException.from(failure));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ServletRequestBindingException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<HrisTimeProblemV1> invalid(
            Exception failure, HttpServletRequest request) {
        log.debug("Invalid scoped Time request; type={}",
                failure.getClass().getSimpleName());
        return writer.response(
                request, HrisTimeApiException.of(HrisTimeProblemCode.SCHEMA_INVALID));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<HrisTimeProblemV1> unexpected(
            Exception failure, HttpServletRequest request) {
        log.error("Scoped Time authority failed; type={}",
                failure.getClass().getSimpleName());
        return writer.response(
                request, HrisTimeApiException.authorityUnavailable(failure));
    }
}
