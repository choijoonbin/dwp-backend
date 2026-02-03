package com.dwp.services.synapsex.dto.actionrecon;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionReconDto {
    private BigDecimal successRate;
    private long totalExecuted;
    private long successCount;
    private long failedCount;
    private List<String> failureReasons;
    private Map<String, Object> impactSummary;
}
