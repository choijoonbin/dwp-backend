package com.dwp.services.synapsex.dto.analysis;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /api/synapse/analysis-runs/{runId} 응답
 */
@Data
@Builder
public class AnalysisRunStatusDto {

    private UUID runId;
    private Long caseId;
    private String status;
    private Integer progress;
    private Instant startedAt;
    private Instant finishedAt;
    private String error;
}
