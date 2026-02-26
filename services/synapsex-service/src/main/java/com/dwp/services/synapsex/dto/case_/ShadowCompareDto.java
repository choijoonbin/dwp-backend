package com.dwp.services.synapsex.dto.case_;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class ShadowCompareDto {
    private Long caseId;
    private UUID runId;
    private String primaryAgent;
    private String shadowAgent;
    private Boolean verdictMatch;
    private BigDecimal scoreDelta;
    private BigDecimal citationCoverageDelta;
    private String holdReasonDelta;
}
