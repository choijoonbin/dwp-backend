package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * PUT /api/synapse/agents/{id} — 에이전트 설정 업데이트
 * 전송된 필드만 갱신. 프롬프트 변경 시 새 버전 추가 및 is_current 전환.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateAgentRequest {

    @Size(max = 255)
    private String name;

    @Size(max = 100)
    private String domain;

    @Size(max = 255)
    private String modelName;

    private BigDecimal temperature;
    private Integer maxTokens;

    private Boolean isActive;

    /** 변경 시 새 prompt_history 행 추가, is_current 플래그 전환 */
    private String systemInstruction;

    /** 도구 매핑을 이 목록으로 교체. null이면 기존 유지 */
    private List<Long> toolIds;
}
