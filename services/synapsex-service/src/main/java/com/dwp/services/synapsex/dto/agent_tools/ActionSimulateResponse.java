package com.dwp.services.synapsex.dto.agent_tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /agent-tools/actions/simulate 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionSimulateResponse {

    private JsonNode beforePreview;
    private JsonNode afterPreview;
    private List<String> validationErrors;
    private JsonNode predictedSapImpact;
    private Long simulationId;  // DB 기록 시
}
