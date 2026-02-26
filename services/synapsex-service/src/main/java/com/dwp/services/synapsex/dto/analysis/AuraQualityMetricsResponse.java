package com.dwp.services.synapsex.dto.analysis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuraQualityMetricsResponse {
    private Instant from;
    private Instant to;
    private Long totalCount;
    private Long sentenceCitationMissingCount;
    private Long evidenceCoverageLowCount;
    private Long policyReevalAppliedCount;
    private Long ragZeroCount;
    private BigDecimal sentenceCitationMissingRatio;
    private BigDecimal evidenceCoverageLowRatio;
    private BigDecimal policyReevalAppliedRatio;
    private BigDecimal ragZeroRatio;
}
