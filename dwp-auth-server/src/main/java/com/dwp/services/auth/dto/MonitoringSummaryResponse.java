package com.dwp.services.auth.dto;

import com.dwp.services.auth.dto.monitoring.MonitoringSummaryKpi;
import lombok.*;

/**
 * 모니터링 요약 응답
 * 기존 필드 유지 + data.kpi 추가 (SLI/SLO 4종)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitoringSummaryResponse {
    private Long pv;
    private Long uv;
    private Long events;
    private Double apiErrorRate;
    private Double pvDeltaPercent;
    private Double uvDeltaPercent;
    private Double eventDeltaPercent;
    private Double apiErrorDeltaPercent;

    /** SLI/SLO KPI (availability, latency, traffic, error) */
    private MonitoringSummaryKpi kpi;
}
