package com.dwp.services.synapsex.dto.agent_tools;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /agent-tools/actions/simulate 요청
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionSimulateRequest {

    @NotNull(message = "caseId is required")
    private Long caseId;

    @NotNull(message = "actionType is required")
    private String actionType;

    private JsonNode payload;
}
