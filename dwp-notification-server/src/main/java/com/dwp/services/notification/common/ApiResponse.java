package com.dwp.services.notification.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String status,
        String message,
        T data,
        String errorCode,
        Instant timestamp,
        Boolean success,
        String correlationId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", null, data, null, Instant.now(), true, null);
    }

    public static <T> ApiResponse<T> error(
            NotificationErrorCode code,
            String message,
            String correlationId) {
        return new ApiResponse<>(
                "ERROR", message, null, code.code(), Instant.now(), false, correlationId);
    }
}
