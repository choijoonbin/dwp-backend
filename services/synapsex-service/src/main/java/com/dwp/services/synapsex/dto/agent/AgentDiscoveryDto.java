package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aura 엔진용 에이전트 디스커버리 응답 DTO
 * 에이전트의 정체성을 파악하기 위한 메타데이터만 포함
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentDiscoveryDto {

    /** Aura 호출 시 사용하는 키 (Snake Case) */
    private String agentKey;

    /** 도메인 (FINANCE, DEVOPS 등) */
    private String domain;

    /** 에이전트 설명 */
    private String description;
}
