package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * POST /api/synapse/cases/{caseId}/analysis-runs 요청
 * caseId는 경로에 있으므로 body의 caseId는 무시
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisRunTriggerRequest {

    private String mode;       // LIVE | SIMULATION
    private String requestedBy; // HUMAN | SYSTEM
    private Map<String, Object> options; // topKSimilar?, ragTopK?
    /** FE 전달 증적 스냅샷. 있으면 DB evidence 대신 Aura에 전달 */
    private JsonNode evidenceSnapshot;
}
