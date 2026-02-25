package com.dwp.services.synapsex.dto.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

/**
 * POST /api/synapse/agents/{id}/knowledge/bind — 요청 본문.
 * doc_id(docId) 하나만 전달.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBindRequest {

    @NotNull(message = "doc_id는 필수입니다.")
    @JsonProperty("doc_id")
    @JsonAlias("docId")
    private Long docId;
}
