package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aura 엔진 런타임 조립용: GET /api/v1/agents/{id}/config 응답
 * 모델 설정, 최신 시스템 프롬프트(agent_prompt_history is_current=true 본문), 활성 도구 리스트.
 * Aura는 systemInstruction 값이 있으면 코드 내부 로직 대신 이 값을 사용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentConfigResponseDto {

    private Long agentId;
    /** Aura 호출 시 사용하는 키 (Snake Case). */
    private String agentKey;
    private String name;
    private String domain;
    private ModelConfigDto model;
    /** agent_prompt_history 최신(is_current=true) 프롬프트 본문. 있으면 Aura가 이 값을 사용. */
    @JsonProperty(value = "systemInstruction", required = false)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String systemInstruction;
    /** agent_prompt_history 현재 버전(version). Aura 콜백 등에서 agent_id·version 전달용. */
    private Integer version;
    private List<AgentToolItemDto> tools;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelConfigDto {
        private String modelName;
        private BigDecimal temperature;
        private Integer maxTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentToolItemDto {
        private String toolName;
        private String description;
        private JsonNode schemaJson;
    }
}
