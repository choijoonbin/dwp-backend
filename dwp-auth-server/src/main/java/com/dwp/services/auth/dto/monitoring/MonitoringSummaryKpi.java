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
        /** 분당 최소 요청 건수(가용성). sys_monitoring_configs AVAILABILITY_MIN_REQ_PER_MINUTE, 다운타임 판정 시 사용 */
        private Integer availabilityMinReqPerMinute;
        /** 5xx 에러율 임계치(가용성, %). sys_monitoring_configs AVAILABILITY_ERROR_RATE_THRESHOLD, 이 값 초과 시 해당 1분을 장애로 집계 */
        private Double availabilityErrorRateThreshold;
        private Long successCount;
        private Long totalCount;
        private Integer downtimeMinutes;
        /** 가동 시간(분) = 조회 기간 전체 분 - downtimeMinutes (프론트 Uptime 표시용) */
        private Long uptimeMinutes;
        /** 장애 구간: 5xx 에러율 임계치 초과 1분 버킷의 [start, end] 목록 (차트 Red 영역 표시용) */
        private java.util.List<DowntimeInterval> downtimeIntervals;
        /** Health Dots용: 기간을 버킷별로 나눈 상태 이력. timestamp(ISO-8601 UTC), status(UP|WARNING|DOWN|NO_DATA), availability(%) */
        private java.util.List<StatusHistoryItem> statusHistory;
        private DeltaAvailability delta;
        private TopCause topCause;
    }

    /** Health Dots용 버킷 단위 상태 이력 항목 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusHistoryItem {
        /** 버킷 시작 시각 (ISO-8601 UTC) */
        private String timestamp;
        /** UP | WARNING | DOWN | NO_DATA */
        private String status;
        /** 해당 버킷 가용성 % (2xx+3xx/전체*100). NO_DATA면 0 */
        private Double availability;
        /** 해당 구간 성공 API 건수 (2xx+3xx) */
        private Long apiCount;
        /** 해당 구간 API 에러 건수 (4xx+5xx) */
        private Long apiErrorCount;
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
        /** 기간(from~to) 평균 RPS */
        private Double rpsAvg;
        /** 기간 내 1분 단위 최대 RPS (Peak RPS) */
        private Double rpsPeak;
        /** 최근 10초간 평균 초당 요청 수 (실시간) */
        private Double currentRps;
        /** 전일 동시간대 10초 구간 RPS (변동률 산출용) */
        private Double prevRps;
        /** 선택 기간 내 총 API 호출 수 (totalPv) */
        private Long totalPv;
        /** 선택 기간 내 중복 제거 클라이언트 수 (IP 또는 User ID 기준 UV) */
        private Long totalUv;
        /** 기간 내 1분 버킷 중 최고 RPS (rpsPeak와 동일값, 명시용) */
        private Double peakRps;
        /** 트래픽 SLO 목표(RPS). sys_monitoring_configs TRAFFIC_SLO_TARGET, 정상 범위 상한 */
        private Double sloTarget;
        /** 트래픽 Critical 임계치(RPS). sys_monitoring_configs TRAFFIC_CRITICAL_THRESHOLD, 초과 시 서버 수용 한계 */
        private Double criticalThreshold;
        /** 부하율(%): (currentRps / criticalThreshold) × 100. 상태 컬러링·서버 증설 신호(Red) 판단용. 100 초과 가능. */
        private Double loadPercentage;
        /** Peak RPS 집계 윈도우(초). sys_monitoring_configs TRAFFIC_PEAK_WINDOW_SECONDS, 기본 60=1분 버킷 */
        private Integer trafficPeakWindowSeconds;
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
        /** 전일 동시간대 대비 RPS 변동률 (%) — (currentRps - prevRps) / prevRps * 100 */
        private Double rpsDeltaPercent;
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
     * Error KPI: 4xx/5xx 분리 집계, Error Budget(SLO), Top Error Path, Delta(이전 기간 대비).
     * errorRate/errorCounts/errorBudgetRemaining/burnRate는 에러 카드 판정·게이지용.
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
        /** 현재 실시간 에러율(%) — 5xx 비율과 동일. Error 카드 메인 지표 */
        private Double errorRate;
        /** 목표 에러율(%). sys_monitoring_configs ERROR_RATE_SLO_TARGET, Error Budget·burnRate 계산에 사용 */
        private Double errorRateSloTarget;
        /** 기준 기간 내 에러 예산 총량. sys_monitoring_configs ERROR_BUDGET_TOTAL */
        private Double errorBudgetTotal;
        /** 4xx·5xx 각각 합계 (errorCounts.count4xx, errorCounts.count5xx) */
        private ErrorCounts errorCounts;
        /** 남은 에러 버짓 퍼센트(%). 0 미만이면 0 */
        private Double errorBudgetRemaining;
        /** 버짓 소진 속도. 1.0 이상이면 위험 */
        private Double burnRate;
        private DeltaError delta;
        private ErrorBudget budget;
        private TopError topError;
    }

    /** 4xx·5xx 건수 집계 (errorCounts 객체용) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorCounts {
        private Long count4xx;
        private Long count5xx;
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

    /** SLO 기반 에러 예산: 허용 에러율은 sys_monitoring_configs ERROR_RATE_SLO_TARGET(기본 0.5%). consumedRatio = min(rate5xx/target, 1.0) */
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
