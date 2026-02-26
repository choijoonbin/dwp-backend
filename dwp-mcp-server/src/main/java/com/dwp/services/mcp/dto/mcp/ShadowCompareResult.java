package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ShadowCompareResult {
    private Instant from;
    private Instant to;
    private Long total;
    private BigDecimal sameVerdictRate;
    private BigDecimal citationMismatchRate;
    private BigDecimal ragZeroRate;
    private BigDecimal holdRate;
    private String decisionCode;
    private List<String> evidenceRefs;
}

