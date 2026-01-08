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
    
    // 성공 응답 생성 메서드
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message("요청이 성공적으로 처리되었습니다.")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message("요청이 성공적으로 처리되었습니다.")
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    // 에러 응답 생성 메서드
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(errorCode.getMessage())
                .errorCode(errorCode.getCode())
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String customMessage) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(customMessage)
                .errorCode(errorCode.getCode())
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .errorCode(errorCode)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
