package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * ICC 대시보드 요약 — 한 번에 제공.
 * GET /api/v1/synapse/dashboard/summary 응답.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SynapseDashboardSummaryDto {

    private Instant asOf;

    /** analytics_kpi_daily: 오늘 기준 최대 4종 KPI */
    private List<KpiDailyItemDto> kpiDaily;

    /** agent_activity_log: 최근 10건 (reasoning 포함) */
    private List<DashboardActivitySummaryDto> recentActivity;

    /** recon_result: FAIL 건수 + 최신 5건 */
    private ReconFailSummaryDto reconFail;

    /** Phase 6: fi_doc_header 시나리오 전표 통계 (위반 3 + 정상 2 등 위험도별 카운트) */
    private FiDocScenarioStatsDto fiDocScenarioStats;
}
