package com.dwp.services.synapsex.dto.workbench;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * GET /api/synapse/workbench/cases/{caseId}/history 응답 1건.
 * DB action_at/created_at → API actionAt/createdAt (camelCase).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CaseActionHistoryItemDto {

    private Long id;
    private Long caseId;
    private String actionType;
    private String actorId;
    private String commentText;
    @JsonProperty("actionAt")
    private Instant actionAt;
    private Map<String, Object> metadataJson;
    @JsonProperty("createdAt")
    private Instant createdAt;
}
