package com.dwp.services.main.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AI 에이전트 작업 생성 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentTaskRequest {
    
    @NotBlank(message = "작업 유형은 필수입니다")
    private String taskType;
    
    @NotBlank(message = "사용자 ID는 필수입니다")
    private String userId;
    
    @NotBlank(message = "테넌트 ID는 필수입니다")
    private String tenantId;
    
    private String description;
    
    /**
     * 작업 입력 데이터 (JSON 문자열)
     */
    private String inputData;
}
