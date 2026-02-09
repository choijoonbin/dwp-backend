package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Aura POST /aura/cases/{caseId}/analysis-runs 응답
 * Aura 스펙: status=ACCEPTED, caseId, streamUrl=/aura/analysis-runs/{runId}/stream
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraAnalyzeResponse {

    private String status;  // ACCEPTED | STARTED | disabled
    private Long caseId;
    private UUID runId;
    private String streamUrl;  // /aura/analysis-runs/{runId}/stream
    private String message;   // DEMO_OFF 시: "Analysis disabled (DEMO_OFF)"
}
