package com.dwp.services.synapsex.dto.workbench;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * GET /api/synapse/workbench/cases/{caseId}/history 응답 1건.
 * agent_case_action_history 기반 (누가, 어떤 조치, 코멘트, 시각).
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
    private Instant actionAt;
    private Map<String, Object> metadataJson;
    private Instant createdAt;
}
