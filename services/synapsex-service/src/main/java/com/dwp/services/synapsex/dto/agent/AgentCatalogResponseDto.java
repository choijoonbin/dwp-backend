package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** GET /api/synapse/agents/catalog — 도메인·문서타입·모델·도구 한꺼번에 (app_codes + tool inventory) */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentCatalogResponseDto {

    private List<KeyValueItem> domains;
    private List<KeyValueItem> docTypes;
    private List<KeyValueItem> models;
    private List<AgentToolCatalogItemDto> tools;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyValueItem {
        private String key;
        private String value;
    }
}
