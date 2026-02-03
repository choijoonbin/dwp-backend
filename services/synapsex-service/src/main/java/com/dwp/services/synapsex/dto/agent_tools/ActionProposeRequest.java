package com.dwp.services.synapsex.dto.agent_tools;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /agent-tools/actions/propose 요청
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionProposeRequest {

    @NotNull(message = "caseId is required")
    private Long caseId;

    @NotNull(message = "actionType is required")
    private String actionType;

    private JsonNode payload;
}
