package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 에이전트 상세 응답 (생성/수정 후 또는 목록 상세)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentDetailDto {

    private Long agentId;
    private String agentKey;
    private String name;
    private String domain;
    private String modelName;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Boolean isActive;
    private String systemInstruction;
    private Integer promptVersion;
    private List<Long> toolIds;
    private Instant createdAt;
    private Instant updatedAt;
}
