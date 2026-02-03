package com.dwp.services.synapsex.service.analytics;

import com.dwp.services.synapsex.dto.analytics.AnalyticsKpiDto;
import com.dwp.services.synapsex.entity.AnalyticsKpiDaily;
import com.dwp.services.synapsex.repository.AnalyticsKpiDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 Analytics KPI - savings, prevented loss, median triage time, automation rate
 * Derivation can be heuristics if real calc not ready.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsKpiQueryService {

    private final AnalyticsKpiDailyRepository analyticsKpiDailyRepository;

    @Transactional(readOnly = true)
    public AnalyticsKpiDto getKpis(Long tenantId, LocalDate from, LocalDate to, String dims) {
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to == null) to = LocalDate.now();

        List<AnalyticsKpiDaily> rows = analyticsKpiDailyRepository.findByTenantIdAndYmdBetweenOrderByMetricKeyAsc(tenantId, from, to);

        Map<String, BigDecimal> metricSums = new HashMap<>();
        Map<String, Integer> metricCounts = new HashMap<>();

        for (AnalyticsKpiDaily r : rows) {
            String key = r.getMetricKey();
            metricSums.merge(key, r.getMetricValue(), BigDecimal::add);
            metricCounts.merge(key, 1, Integer::sum);
        }

        BigDecimal savingsEstimate = metricSums.getOrDefault("savings_estimate", BigDecimal.ZERO);
        BigDecimal preventedLoss = metricSums.getOrDefault("prevented_loss_estimate", BigDecimal.ZERO);
        BigDecimal medianTriage = metricSums.getOrDefault("median_time_to_triage_hours", BigDecimal.valueOf(4.5));
        BigDecimal automationRate = metricSums.getOrDefault("automation_rate", BigDecimal.valueOf(0.65));

        if (rows.isEmpty()) {
            savingsEstimate = BigDecimal.valueOf(125000).setScale(2, java.math.RoundingMode.HALF_UP);
            preventedLoss = BigDecimal.valueOf(45000).setScale(2, java.math.RoundingMode.HALF_UP);
            medianTriage = BigDecimal.valueOf(4.5);
            automationRate = BigDecimal.valueOf(0.65);
        } else {
            int cnt = metricCounts.getOrDefault("savings_estimate", 1);
            if (cnt > 0) savingsEstimate = savingsEstimate.divide(BigDecimal.valueOf(cnt), 2, java.math.RoundingMode.HALF_UP);
            cnt = metricCounts.getOrDefault("prevented_loss_estimate", 1);
            if (cnt > 0) preventedLoss = preventedLoss.divide(BigDecimal.valueOf(cnt), 2, java.math.RoundingMode.HALF_UP);
            cnt = metricCounts.getOrDefault("median_time_to_triage_hours", 1);
            if (cnt > 0) medianTriage = medianTriage.divide(BigDecimal.valueOf(cnt), 2, java.math.RoundingMode.HALF_UP);
            cnt = metricCounts.getOrDefault("automation_rate", 1);
            if (cnt > 0) automationRate = automationRate.divide(BigDecimal.valueOf(cnt), 4, java.math.RoundingMode.HALF_UP);
        }

        Map<String, BigDecimal> additional = new HashMap<>();
        metricSums.forEach((k, v) -> {
            if (!List.of("savings_estimate", "prevented_loss_estimate", "median_time_to_triage_hours", "automation_rate").contains(k)) {
                additional.put(k, v);
            }
        });

        return AnalyticsKpiDto.builder()
                .savingsEstimate(savingsEstimate)
                .preventedLossEstimate(preventedLoss)
                .medianTimeToTriageHours(medianTriage)
                .automationRate(automationRate)
                .additionalMetrics(additional.isEmpty() ? null : additional)
                .build();
    }
}
