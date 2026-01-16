package com.dwp.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 표준 API 응답 DTO
 * 
 * AI 에이전트가 파싱하기 쉽도록 명확한 구조를 유지합니다:
 * - status: SUCCESS 또는 ERROR (명확한 성공/실패 구분)
 * - message: 인간이 읽을 수 있는 메시지
 * - data: 실제 응답 데이터 (제네릭 타입)
 * - errorCode: 에러 발생 시 기계가 읽을 수 있는 코드
 * - timestamp: 응답 생성 시각
 * - success: AI가 빠르게 성공 여부를 판단할 수 있는 boolean 필드
 */
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
    
    /**
     * AI 에이전트가 성공/실패를 빠르게 판단할 수 있도록 추가된 필드
     * status == "SUCCESS" 일 때 true
     */
    @JsonProperty("success")
    private Boolean success;
    
    /**
     * 에이전트 전용 메타데이터 (선택)
     * 
     * AI 에이전트가 응답을 처리하는 데 필요한 추가 정보를 담습니다.
     * 예: 추적 ID, 실행 단계 정보, 신뢰도 점수 등
     */
    @JsonProperty("agentMetadata")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AgentMetadata agentMetadata;
    
    // 성공 응답 생성 메서드
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message("요청이 성공적으로 처리되었습니다.")
                .data(data)
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message(message)
                .data(data)
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .status("SUCCESS")
                .message("요청이 성공적으로 처리되었습니다.")
                .success(true)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    // 에러 응답 생성 메서드
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(errorCode.getMessage())
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String customMessage) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(customMessage)
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(message)
                .errorCode(errorCode)
                .success(false)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
