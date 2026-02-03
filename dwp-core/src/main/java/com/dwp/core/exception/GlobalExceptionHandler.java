package com.dwp.core.exception;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@SuppressWarnings("null")
public class GlobalExceptionHandler {

    private static String traceId(HttpServletRequest req) {
        return req != null ? req.getHeader(HeaderConstants.X_TRACE_ID) : null;
    }

    private static String gatewayRequestId(HttpServletRequest req) {
        return req != null ? req.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID) : null;
    }
    
    /**
     * 커스텀 BaseException 처리 (errorCode/errorMessage/traceId/gatewayRequestId 통일)
     */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Object>> handleBaseException(BaseException e, HttpServletRequest request) {
        log.error("BaseException: [{}] {}", e.getErrorCode().getCode(), e.getMessage(), e);
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage(), traceId(request), gatewayRequestId(request)));
    }
    
    /**
     * @Valid 검증 실패 처리 (400)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        log.warn("Validation error: {}", errors);
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "입력값 검증에 실패했습니다.", traceId(request), gatewayRequestId(request)));
    }
    
    /**
     * @ModelAttribute 검증 실패 처리 (400)
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBindException(BindException e, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        log.warn("Bind error: {}", errors);
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, "입력값 검증에 실패했습니다.", traceId(request), gatewayRequestId(request)));
    }
    
    /**
     * 필수 요청 헤더 누락 처리 (400, 예: X-Tenant-ID)
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingRequestHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        log.warn("Missing required header: {}", e.getHeaderName());
        return ResponseEntity
                .status(ErrorCode.VALIDATION_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR,
                        String.format("필수 헤더 '%s'가 누락되었습니다.", e.getHeaderName()), traceId(request), gatewayRequestId(request)));
    }

    /**
     * 파라미터 타입 불일치 처리 (400)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatchException(
            MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.warn("Type mismatch error: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, 
                        String.format("파라미터 '%s'의 타입이 올바르지 않습니다.", e.getName()), traceId(request), gatewayRequestId(request)));
    }
    
    /**
     * IllegalArgumentException 처리 (400)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgumentException(
            IllegalArgumentException e, HttpServletRequest request) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, e.getMessage(), traceId(request), gatewayRequestId(request)));
    }
    
    /**
     * 기타 예상치 못한 예외 처리 (500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(Exception e, HttpServletRequest request) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR, traceId(request), gatewayRequestId(request)));
    }
}
