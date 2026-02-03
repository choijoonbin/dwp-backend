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

    /**
     * 추적 ID (에러 시 필수 포함, 운영 추적용)
     */
    @JsonProperty("traceId")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String traceId;

    /**
     * Gateway 요청 ID (멱등성/중복 요청 추적)
     */
    @JsonProperty("gatewayRequestId")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String gatewayRequestId;

    /**
     * Audit ID (Action 실패 시 FE "Audit 상세 보기" 링크용)
     */
    @JsonProperty("auditId")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String auditId;
    
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

    /**
     * payload 없이 성공만 반환할 때 사용 (삭제/역할 저장 등).
     * FE 계약: 성공 시 {@code data.success === true} 로 판단할 수 있도록 {@code data: { "success": true }} 를 반환합니다.
     */
    public static ApiResponse<java.util.Map<String, Boolean>> successOk() {
        return ApiResponse.<java.util.Map<String, Boolean>>builder()
                .status("SUCCESS")
                .message("요청이 성공적으로 처리되었습니다.")
                .data(java.util.Map.of("success", true))
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

    /**
     * traceId/gatewayRequestId 포함 에러 (운영 추적용)
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String customMessage, String traceId, String gatewayRequestId) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(customMessage)
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .traceId(traceId)
                .gatewayRequestId(gatewayRequestId)
                .build();
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String traceId, String gatewayRequestId) {
        return error(errorCode, errorCode.getMessage(), traceId, gatewayRequestId);
    }

    /**
     * auditId 포함 에러 (Action simulate/approve/execute 실패 시 FE Audit 링크용)
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String customMessage,
                                            String auditId, String traceId, String gatewayRequestId) {
        return ApiResponse.<T>builder()
                .status("ERROR")
                .message(customMessage)
                .errorCode(errorCode.getCode())
                .success(false)
                .timestamp(LocalDateTime.now())
                .auditId(auditId)
                .traceId(traceId)
                .gatewayRequestId(gatewayRequestId)
                .build();
    }
}
