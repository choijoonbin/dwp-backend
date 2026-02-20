package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * GET /api/synapse/agents/knowledge — 지식 베이스(RAG) 카탈로그 항목 (FE '지식' 탭)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KnowledgeCatalogItemDto {

    private Long docId;
    private String title;
    private String sourceType;
    private String docType;
    private String status;
    private Boolean isBound;
    private Instant createdAt;
}
