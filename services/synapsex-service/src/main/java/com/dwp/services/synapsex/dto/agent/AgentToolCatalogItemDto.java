package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /api/synapse/agents/catalog, /tools — 도구 카탈로그. schemaJson 있으면 FE에서 도구별 설정 UI 동적 렌더링용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentToolCatalogItemDto {

    private Long toolId;
    private String toolName;
    private String description;
    /** 파라미터 규격(JSON Schema 등). 비어있지 않으면 항상 내려줌 — FE 도구별 설정 동적 확장용. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private JsonNode schemaJson;
}
