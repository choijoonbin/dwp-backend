package com.dwp.services.synapsex.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsKpiDto {
    private BigDecimal savingsEstimate;
    private BigDecimal preventedLossEstimate;
    private BigDecimal medianTimeToTriageHours;
    private BigDecimal automationRate;
    private Map<String, BigDecimal> additionalMetrics;
}
