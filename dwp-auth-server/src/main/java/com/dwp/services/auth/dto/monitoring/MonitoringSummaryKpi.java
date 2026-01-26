package com.dwp.services.auth.dto.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 모니터링 Summary KPI 블록 (SLI/SLO 4종)
 * GET /api/admin/monitoring/summary 응답의 data.kpi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringSummaryKpi {

    private AvailabilityKpi availability;
    private LatencyKpi latency;
    private TrafficKpi traffic;
    private ErrorKpi error;

    /** 가용성: successRate(2xx+3xx/전체*100), sloTargetSuccessRate(DB 설정), downtimeMinutes, uptimeMinutes */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AvailabilityKpi {
        /** 성공률 % (전체 요청 0이면 100.0) */
        private Double successRate;
        /** 가용성 SLO 목표 성공률 % (sys_monitoring_configs AVAILABILITY_SLO_TARGET, 기본 99.9) */
        private Double sloTargetSuccessRate;
        /** Critical 임계치 % (sys_monitoring_configs AVAILABILITY_CRITICAL_THRESHOLD, 기본 99.0). successRate < 이 값이면 Critical 배지·빨간색 UI */
        private Double criticalThreshold;
        private Long successCount;
        private Long totalCount;
        private Integer downtimeMinutes;
        /** 가동 시간(분) = 조회 기간 전체 분 - downtimeMinutes (프론트 Uptime 표시용) */
        private Long uptimeMinutes;
        /** 장애 구간: 5xx 에러율 임계치 초과 1분 버킷의 [start, end] 목록 (차트 Red 영역 표시용) */
        private java.util.List<DowntimeInterval> downtimeIntervals;
        private DeltaAvailability delta;
        private TopCause topCause;
    }

    /** 장애 1분 구간 (ISO-8601 UTC) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DowntimeInterval {
        private String start;
        private String end;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeltaAvailability {
        private Double successRatePp;     // percentage point
        private Integer downtimeMinutes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopCause {
        private String path;
        private String statusGroup;
        private Long count;
    }

    /** 지연시간(ms): 데이터 없으면 0 반환. sloTarget/criticalThreshold는 DB 설정(동적 판단용) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatencyKpi {
        /** 현재 평균 지연 시간(ms) */
        private Long avgLatency;
        /** 50%ile 중간값(ms), p50Ms와 동일 */
        private Long p50Latency;
        private Long p50Ms;
        private Long p95Ms;
        /** 99%ile 최악값(ms), p99Ms와 동일 */
        private Long p99Latency;
        private Long p99Ms;
        /** 지연 SLO 목표(ms). sys_monitoring_configs LATENCY_SLO_TARGET (예: 500) */
        private Long sloTarget;
        /** 지연 Critical 임계치(ms). sys_monitoring_configs LATENCY_CRITICAL_THRESHOLD (예: 1500), 초과 시 심각 */
        private Long criticalThreshold;
        /** 전일(또는 비교 기간) 평균 지연(ms). 변동률 계산용 */
        private Long prevAvgLatency;
        private DeltaLatency delta;
        private TopSlow topSlow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeltaLatency {
        private Long p95Ms;
        private Long p99Ms;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopSlow {
        private String path;
        private Long p95Ms;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrafficKpi {
        private Double rpsAvg;
        private Double rpsPeak;
        private Long requestCount;
        private Long pv;
        private Long uv;
        private DeltaTraffic delta;
        private TopTraffic topTraffic;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeltaTraffic {
        private Double rpsAvg;
        private Long requestCount;
        private Long pv;
        private Long uv;
        /** 이전 기간 대비 PV 증감 (%) */
        private Double pvDeltaPercent;
        /** 이전 기간 대비 UV 증감 (%) */
        private Double uvDeltaPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopTraffic {
        private String path;
        private Long requestCount;
    }

    /**
     * Error KPI: 4xx/5xx 분리 집계, Error Budget(SLO 99.9%), Top Error Path, Delta(이전 기간 대비).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorKpi {
        /** 전체 요청 대비 4xx 비율(%) */
        private Double rate4xx;
        /** 전체 요청 대비 5xx 비율(%) */
        private Double rate5xx;
        /** 해당 기간 4xx 건수 */
        private Long count4xx;
        /** 해당 기간 5xx 건수 */
        private Long count5xx;
        private DeltaError delta;
        private ErrorBudget budget;
        private TopError topError;
    }

    /** 이전 동일 기간 대비 rate5xx 퍼센트포인트(pP) 증감, count5xx 증감 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeltaError {
        private Double rate5xxPp;
        private Long count5xx;
    }

    /** SLO 기반 에러 예산: 허용 에러율 0.1%, consumedRatio = min(rate5xx/0.1, 1.0), 최대 1.0(100%)로 제한 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorBudget {
        /** 조회 기간과 연동: 1H, 24H, 7D, WEEK */
        private String period;
        private Double sloTargetSuccessRate;
        /** 소진율 0~1.0 (초과 시 1.0으로 cap, Progress Bar 호환) */
        private Double consumedRatio;
    }

    /** 해당 기간 가장 많이 발생한 에러 1건 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopError {
        private String path;
        private Integer statusCode;
        private Long count;
    }
}
