package com.dwp.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AI 에이전트의 사고 과정 단계를 나타내는 DTO
 * 
 * 프론트엔드 Aura AI UI 명세에 맞춰 정의되었습니다.
 * plan_steps 배열의 각 요소를 나타냅니다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentStep {
    
    /**
     * 단계 고유 식별자
     */
    private String id;
    
    /**
     * 단계 제목
     */
    private String title;
    
    /**
     * 단계 설명
     */
    private String description;
    
    /**
     * 단계 상태
     * 예: "pending", "in_progress", "completed", "failed"
     */
    private String status;
    
    /**
     * 단계 실행 신뢰도 (0.0 ~ 1.0)
     * AI가 이 단계를 실행할 확신 정도를 나타냅니다.
     */
    private Double confidence;
    
    /**
     * 단계 실행 결과 (선택)
     */
    private Object result;
    
    /**
     * 단계 실행 시작 시간 (Unix timestamp)
     */
    private Long startedAt;
    
    /**
     * 단계 실행 완료 시간 (Unix timestamp)
     */
    private Long completedAt;
    
    /**
     * 프론트엔드 UI 탭 그룹
     * 프론트엔드에서 이 단계를 표시할 탭을 지정합니다.
     * 
     * 가능한 값:
     * - "thought" 또는 "thinking": 사고 과정 탭
     * - "plan" 또는 "work_plan": 작업 계획 탭
     * - "execution" 또는 "execution_log": 실행 로그 탭
     * - "result" 또는 "results": 결과 탭
     * 
     * null인 경우 프론트엔드가 자동으로 분류합니다.
     */
    private String tabGroup;
}
