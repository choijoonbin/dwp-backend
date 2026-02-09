package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * POST /api/synapse/cases/{caseId}/analysis-runs 응답
 * Phase2 202: 항상 202 Accepted, data.runId, data.status=STARTED, data.streamUrl
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisRunTriggerResponse {

    private UUID runId;
    private String status;  // STARTED
    private String streamUrl;
    private Instant startedAt;  // optional
}
