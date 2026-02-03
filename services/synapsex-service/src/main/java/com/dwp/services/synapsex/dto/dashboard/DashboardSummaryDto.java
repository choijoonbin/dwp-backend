package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GET /api/synapse/dashboard/summary 응답 DTO
 * FE 계약: financialHealthIndex, openCasesBySeverity.{critical,high,medium,low}, aiActionSuccessRate 등
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardSummaryDto {

    private Long tenantId;
    private Instant asOf;

    /** 재무 건전성 지수 (0-100) */
    private Integer financialHealthIndex;
    private BigDecimal financialHealthTrend;

    /** 심각도별 미해결 케이스 (FE: critical, high, medium, low) */
    private OpenCasesBySeverity openCasesBySeverity;

    /** AI 조치 성공률 (%) */
    private BigDecimal aiActionSuccessRate;
    private BigDecimal aiActionSuccessTrend;

    /** 예상 손실 방지액 */
    private BigDecimal estimatedPreventedLoss;
    private BigDecimal preventedLossTrend;

    /** 대기 중 승인 수 */
    private Long pendingApprovals;
    private Long slaAtRisk;
    private BigDecimal avgLeadTime;
    private Long backlogCount;

    /** 에이전트 LIVE 상태 (active|idle|processing) */
    private String agentLiveStatus;

    /** 클릭 동선용 drill-down 링크 */
    private SummaryLinks links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryLinks {
        /** /cases?status=OPEN&severity=... */
        private String casesPath;
        /** /actions?status=PENDING_APPROVAL */
        private String actionsPath;
        /** /audit?category=... */
        private String auditPath;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenCasesBySeverity {
        private long critical;
        private long high;
        private long medium;
        private long low;
    }
}
