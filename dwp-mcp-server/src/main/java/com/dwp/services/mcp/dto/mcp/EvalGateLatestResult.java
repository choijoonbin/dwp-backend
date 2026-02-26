package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class EvalGateLatestResult {
    private Long evalRunId;
    private String runKey;
    private BigDecimal zeroRate;
    private BigDecimal hitAtK;
    private BigDecimal strictHitTop1;
    private Integer totalCases;
    private Boolean persistedGatePassed;
    private Boolean computedGatePassed;
    private BigDecimal thresholdZeroRate;
    private BigDecimal thresholdHitAtK;
    private Instant createdAt;
}

