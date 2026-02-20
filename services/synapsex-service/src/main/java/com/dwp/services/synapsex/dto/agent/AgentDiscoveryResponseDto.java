package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aura 엔진용 에이전트 디스커버리 응답 래퍼
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentDiscoveryResponseDto {

    private List<AgentDiscoveryDto> agents;
}
