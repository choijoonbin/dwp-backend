package com.dwp.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private String status;
    private String message;
    private T data;
    private String errorCode;
    private LocalDateTime timestamp;
    private Boolean success;
    private String correlationId;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .data(data)
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMessage());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> error(
            ErrorCode errorCode,
            String message,
            String correlationId) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .correlationId(correlationId)
                .build();
    }

    public static <T> ApiResponse<T> error(
            ErrorCode errorCode,
            String message,
            T data,
            String correlationId) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .data(data)
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .correlationId(correlationId)
                .build();
    }
}
