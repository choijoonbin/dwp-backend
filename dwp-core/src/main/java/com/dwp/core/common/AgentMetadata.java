package com.dwp.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * AI 에이전트 전용 메타데이터
 * 
 * ApiResponse에 포함되어 에이전트가 응답을 처리하는 데 필요한 추가 정보를 제공합니다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentMetadata {
    
    /**
     * 추적 ID (요청 추적용)
     */
    private String traceId;
    
    /**
     * 실행 단계 정보
     */
    private List<AgentStep> steps;
    
    /**
     * 신뢰도 점수 (0.0 ~ 1.0)
     */
    private Double confidence;
    
    /**
     * 추가 메타데이터 (키-값 쌍)
     */
    private Map<String, Object> additionalData;
}
