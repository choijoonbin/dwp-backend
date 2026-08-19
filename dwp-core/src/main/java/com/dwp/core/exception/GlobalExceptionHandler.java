package com.dwp.core.exception;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@SuppressWarnings("null")
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String errorMessage(ErrorCode errorCode, Locale locale) {
        return messageSource.getMessage(
                "error." + errorCode.getCode(),
                null,
                errorCode.getMessage(),
                locale);
    }

    private String message(String key, Object[] arguments, String fallback, Locale locale) {
        return messageSource.getMessage(key, arguments, fallback, locale);
    }

    private static String correlationId(HttpServletRequest request) {
        return request == null ? null : request.getHeader(HeaderConstants.X_CORRELATION_ID);
    }

    private static Map<String, String> validationErrors(Iterable<ObjectError> objectErrors) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ObjectError error : objectErrors) {
            String path = error instanceof FieldError fieldError
                    ? fieldError.getField()
                    : error.getObjectName();
            errors.put(path, error.getDefaultMessage());
        }
        return errors;
    }
    
    /**
     * 커스텀 BaseException 처리
     */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Object>> handleBaseException(
            BaseException e,
            HttpServletRequest request,
            Locale locale) {
        log.error("BaseException: [{}] {}", e.getErrorCode().getCode(), e.getMessage(), e);
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(
                        e.getErrorCode(),
                        errorMessage(e.getErrorCode(), locale),
                        correlationId(request)));
    }
    
    /**
     * @Valid 검증 실패 처리 (400)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException e,
            HttpServletRequest request,
            Locale locale) {
        Map<String, String> errors = validationErrors(e.getBindingResult().getAllErrors());
        
        log.warn("Validation error: {}", errors);
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(
                        ErrorCode.VALIDATION_ERROR,
                        errorMessage(ErrorCode.VALIDATION_ERROR, locale),
                        errors,
                        correlationId(request)));
    }
    
    /**
     * @ModelAttribute 검증 실패 처리 (400)
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBindException(
            BindException e,
            HttpServletRequest request,
            Locale locale) {
        Map<String, String> errors = validationErrors(e.getBindingResult().getAllErrors());
        
        log.warn("Bind error: {}", errors);
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(
                        ErrorCode.VALIDATION_ERROR,
                        errorMessage(ErrorCode.VALIDATION_ERROR, locale),
                        errors,
                        correlationId(request)));
    }
    
    /**
     * 필수 요청 헤더 누락 처리 (400, 예: X-Tenant-ID)
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingRequestHeader(
            MissingRequestHeaderException e,
            HttpServletRequest request,
            Locale locale) {
        log.warn("Missing required header: {}", e.getHeaderName());
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR,
                        message(
                                "request.missing-header",
                                new Object[]{e.getHeaderName()},
                                "Required header ''{0}'' is missing.",
                                locale),
                        correlationId(request)));
    }

    /**
     * 필수 요청 파라미터 누락 처리 (400)
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingRequestParameter(
            MissingServletRequestParameterException e,
            HttpServletRequest request,
            Locale locale) {
        log.warn("Missing required parameter: {}", e.getParameterName());
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR,
                        message(
                                "request.missing-parameter",
                                new Object[]{e.getParameterName()},
                                "Required parameter ''{0}'' is missing.",
                                locale),
                        correlationId(request)));
    }

    /**
     * 파라미터 타입 불일치 처리 (400)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatchException(
            MethodArgumentTypeMismatchException e,
            HttpServletRequest request,
            Locale locale) {
        log.warn("Type mismatch error: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, 
                        message(
                                "request.type-mismatch",
                                new Object[]{e.getName()},
                                "Parameter ''{0}'' has an invalid type.",
                                locale),
                        correlationId(request)));
    }
    
    /**
     * JSON 역직렬화 실패 처리 (400) — UnrecognizedPropertyException 등
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e,
            HttpServletRequest request,
            Locale locale) {
        String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        log.warn("JSON parse error: {}", msg);
        return ResponseEntity
                .status(ErrorCode.INVALID_FORMAT.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_FORMAT,
                        message(
                                "request.body-invalid",
                                null,
                                "The request body format is invalid.",
                                locale),
                        correlationId(request)));
    }

    /**
     * IllegalArgumentException 처리 (400)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgumentException(
            IllegalArgumentException e,
            HttpServletRequest request,
            Locale locale) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(
                        ErrorCode.INVALID_INPUT_VALUE,
                        errorMessage(ErrorCode.INVALID_INPUT_VALUE, locale),
                        correlationId(request)));
    }

    /**
     * 멀티파트 업로드 용량 초과 처리 (400)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e,
            HttpServletRequest request,
            Locale locale) {
        log.warn("Upload size limit exceeded: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(
                        ErrorCode.INVALID_INPUT_VALUE,
                        message(
                                "request.upload-too-large",
                                null,
                                "The uploaded file exceeds the allowed size.",
                                locale),
                        correlationId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResourceFoundException(
            NoResourceFoundException e,
            HttpServletRequest request,
            Locale locale) {
        log.warn("Resource not found: {}", e.getResourcePath());
        return ResponseEntity
                .status(ErrorCode.NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(
                        ErrorCode.NOT_FOUND,
                        errorMessage(ErrorCode.NOT_FOUND, locale),
                        correlationId(request)));
    }
    
    /**
     * 기타 예상치 못한 예외 처리 (500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(
            Exception e,
            HttpServletRequest request,
            Locale locale) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.error(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        errorMessage(ErrorCode.INTERNAL_SERVER_ERROR, locale),
                        correlationId(request)));
    }
}
