package com.dwp.services.main.dto;

import com.dwp.services.main.domain.TaskStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 에이전트 작업 응답 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTaskResponse {
    
    private String taskId;
    private String userId;
    private String tenantId;
    private String taskType;
    private TaskStatus status;
    private Integer progress;
    private String description;
    private String resultData;
    private String planSteps;  // AI 에이전트의 실행 계획 단계 (JSON)
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
