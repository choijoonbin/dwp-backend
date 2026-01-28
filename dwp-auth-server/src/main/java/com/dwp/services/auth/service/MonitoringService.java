package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.*;
import com.dwp.services.auth.dto.monitoring.MonitoringSummaryKpi;
import com.dwp.services.auth.entity.ApiCallHistory;
import com.dwp.services.auth.entity.PageViewDailyStat;
import com.dwp.services.auth.entity.PageViewEvent;
import com.dwp.services.auth.repository.ApiCallHistoryRepository;
import com.dwp.services.auth.repository.MonitoringConfigRepository;
import com.dwp.services.auth.repository.PageViewDailyStatRepository;
import com.dwp.services.auth.repository.PageViewEventRepository;
import com.dwp.services.auth.repository.projection.LatencyPercentilesView;
import com.dwp.services.auth.repository.projection.TopCauseView;
import com.dwp.services.auth.repository.projection.TopErrorView;
import com.dwp.services.auth.repository.projection.TopSlowView;
import com.dwp.services.auth.repository.projection.TopTrafficView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MonitoringService {

    private final ApiCallHistoryRepository apiCallHistoryRepository;
    private final MonitoringConfigRepository monitoringConfigRepository;
    private final PageViewEventRepository pageViewEventRepository;
    private final PageViewDailyStatRepository pageViewDailyStatRepository;
    private final ObjectMapper objectMapper;

    /** 모니터링 설정 키 (sys_codes 코드값) */
    private static final String CODE_MIN_REQ_PER_MINUTE = "MIN_REQ_PER_MINUTE";
    private static final String CODE_ERROR_RATE_THRESHOLD = "ERROR_RATE_THRESHOLD";
    private static final String CODE_AVAILABILITY_SLO_TARGET = "AVAILABILITY_SLO_TARGET";
    private static final String CODE_AVAILABILITY_CRITICAL_THRESHOLD = "AVAILABILITY_CRITICAL_THRESHOLD";
    private static final String CODE_LATENCY_SLO_TARGET = "LATENCY_SLO_TARGET";
    private static final String CODE_LATENCY_CRITICAL_THRESHOLD = "LATENCY_CRITICAL_THRESHOLD";
    private static final String CODE_TRAFFIC_SLO_TARGET = "TRAFFIC_SLO_TARGET";
    private static final String CODE_TRAFFIC_CRITICAL_THRESHOLD = "TRAFFIC_CRITICAL_THRESHOLD";
    private static final String CODE_TRAFFIC_PEAK_WINDOW_SECONDS = "TRAFFIC_PEAK_WINDOW_SECONDS";
    private static final String CODE_ERROR_RATE_SLO_TARGET = "ERROR_RATE_SLO_TARGET";
    private static final String CODE_ERROR_BUDGET_TOTAL = "ERROR_BUDGET_TOTAL";
    private static final int DEFAULT_MIN_REQ_PER_MINUTE = 1;
    private static final double DEFAULT_ERROR_RATE_THRESHOLD = 5.0;
    private static final double DEFAULT_AVAILABILITY_SLO_TARGET = 99.9;
    private static final double DEFAULT_AVAILABILITY_CRITICAL_THRESHOLD = 99.0;
    private static final long DEFAULT_LATENCY_SLO_TARGET = 500L;
    private static final long DEFAULT_LATENCY_CRITICAL_THRESHOLD = 1500L;
    private static final double DEFAULT_TRAFFIC_SLO_TARGET = 100.0;
    private static final double DEFAULT_TRAFFIC_CRITICAL_THRESHOLD = 200.0;
    private static final int DEFAULT_TRAFFIC_PEAK_WINDOW_SECONDS = 60;
    private static final double DEFAULT_ERROR_RATE_SLO_TARGET = 0.5;
    private static final double DEFAULT_ERROR_BUDGET_TOTAL = 100.0;

    /**
     * API 호출 이력 비동기 저장 (Best-effort)
     */
    @Async
    @Transactional
    public void recordApiCallHistory(ApiCallHistoryRequest request) {
        try {
            ApiCallHistory history = ApiCallHistory.builder()
                    .tenantId(request.getTenantId())
                    .userId(request.getUserId())
                    .agentId(request.getAgentId())
                    .method(request.getMethod())
                    .path(request.getPath())
                    .queryString(request.getQueryString())
                    .statusCode(request.getStatusCode())
                    .latencyMs(request.getLatencyMs())
                    .requestSizeBytes(request.getRequestSizeBytes())
                    .responseSizeBytes(request.getResponseSizeBytes())
                    .ipAddress(request.getIpAddress())
                    .userAgent(request.getUserAgent())
                    .traceId(request.getTraceId())
                    .errorCode(request.getErrorCode())
                    .source(request.getSource())
                    .build();
            apiCallHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to record API call history", e);
        }
    }

    /**
     * 페이지뷰 기록 및 일별 집계 업데이트
     */
    @Transactional
    public void recordPageView(Long tenantId, Long userId, String ipAddress, String userAgent, PageViewRequest request) {
        try {
            // 1. Raw 이벤트 저장
            PageViewEvent event = PageViewEvent.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .sessionId(request.getVisitorId())
                    .pageKey(request.getPageKey() != null ? request.getPageKey() : request.getPath())
                    .referrer(request.getReferrer())
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .eventType("PAGE_VIEW")
                    .eventName(request.getRouteName())
                    .metadataJson(toJson(request.getMetadata()))
                    .build();
            pageViewEventRepository.save(event);

            // 2. 일별 집계 업데이트 (Upsert)
            updateDailyStats(tenantId, event.getPageKey(), request.getVisitorId());
        } catch (Exception e) {
            log.error("Failed to record page view", e);
        }
    }

    /**
     * 일반 이벤트 기록
     */
    @Transactional
    public void recordEvent(Long tenantId, Long userId, String ipAddress, String userAgent, EventRequest request) {
        try {
            PageViewEvent event = PageViewEvent.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .sessionId(request.getVisitorId())
                    .pageKey(request.getPath())
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .eventType(request.getEventType())
                    .eventName(request.getEventName())
                    .targetKey(request.getTargetKey())
                    .metadataJson(toJson(request.getMetadata()))
                    .build();
            pageViewEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to record event", e);
        }
    }

    private void updateDailyStats(Long tenantId, String pageKey, String visitorId) {
        LocalDate today = LocalDate.now();
        Optional<PageViewDailyStat> statOpt = pageViewDailyStatRepository.findByTenantIdAndStatDateAndPageKey(tenantId, today, pageKey);

        if (statOpt.isPresent()) {
            PageViewDailyStat stat = statOpt.get();
            stat.setPvCount(stat.getPvCount() + 1);
            // UV(유니크 세션) 처리는 실제로는 더 정교해야 하지만, P0-3에서는 단순 증가 또는 별도 배치를 권장.
            // 여기서는 단순 데모 수준으로 구현.
            pageViewDailyStatRepository.save(stat);
        } else {
            PageViewDailyStat stat = PageViewDailyStat.builder()
                    .tenantId(tenantId)
                    .statDate(today)
                    .pageKey(pageKey)
                    .pvCount(1L)
                    .uvCount(1L) // 실제로는 visitorId 기반 unique 체크 필요
                    .uniqueSessionCount(1L)
                    .build();
            pageViewDailyStatRepository.save(stat);
        }
    }

    /**
     * 대시보드 요약 정보 조회 (compare 미지정 시 직전 동일 기간 자동 계산)
     */
    @Transactional(readOnly = true)
    public MonitoringSummaryResponse getSummary(Long tenantId, LocalDateTime from, LocalDateTime to,
                                                 LocalDateTime compareFrom, LocalDateTime compareTo) {
        long durationSec = ChronoUnit.SECONDS.between(from, to);
        if (durationSec <= 0) durationSec = 1;
        if (compareFrom == null || compareTo == null) {
            compareTo = from;
            compareFrom = from.minus(durationSec, ChronoUnit.SECONDS);
        }

        long currentPv = pageViewEventRepository.countPvByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentUv = pageViewEventRepository.countUvByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentEvents = pageViewEventRepository.countEventsByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentApiTotal = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentApiErrors = apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, from, to);

        long prevPv = pageViewEventRepository.countPvByTenantIdAndCreatedAtBetween(tenantId, compareFrom, compareTo);
        long prevUv = pageViewEventRepository.countUvByTenantIdAndCreatedAtBetween(tenantId, compareFrom, compareTo);
        long prevEvents = pageViewEventRepository.countEventsByTenantIdAndCreatedAtBetween(tenantId, compareFrom, compareTo);
        long prevApiTotal = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, compareFrom, compareTo);
        long prevApiErrors = apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, compareFrom, compareTo);

        double apiErrorRate = currentApiTotal > 0 ? (double) currentApiErrors / currentApiTotal * 100 : 0.0;
        double prevApiErrorRate = prevApiTotal > 0 ? (double) prevApiErrors / prevApiTotal * 100 : 0.0;

        MonitoringSummaryKpi kpiCurrent = buildKpi(tenantId, from, to, currentPv, currentUv, currentApiTotal);
        MonitoringSummaryKpi kpiCompare = buildKpi(tenantId, compareFrom, compareTo, prevPv, prevUv, prevApiTotal);
        if (kpiCurrent.getLatency() != null && kpiCompare.getLatency() != null) {
            Long prevAvg = kpiCompare.getLatency().getAvgLatency();
            kpiCurrent.getLatency().setPrevAvgLatency(prevAvg != null ? prevAvg : 0L);
        }
        attachDeltas(kpiCurrent, kpiCompare);
        attachTrafficRealtimeRps(tenantId, kpiCurrent);

        return MonitoringSummaryResponse.builder()
                .pv(currentPv)
                .uv(currentUv)
                .events(currentEvents)
                .apiErrorRate(apiErrorRate)
                .pvDeltaPercent(calculateDelta(currentPv, prevPv))
                .uvDeltaPercent(calculateDelta(currentUv, prevUv))
                .eventDeltaPercent(calculateDelta(currentEvents, prevEvents))
                .apiErrorDeltaPercent(calculateDelta(apiErrorRate, prevApiErrorRate))
                .kpi(kpiCurrent)
                .build();
    }

    private MonitoringSummaryKpi buildKpi(Long tenantId, LocalDateTime from, LocalDateTime to, long pv, long uv, long requestCount) {
        long totalCount = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long successCount = apiCallHistoryRepository.countSuccessByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long count4xx = apiCallHistoryRepository.count4xxByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long count5xx = apiCallHistoryRepository.count5xxByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long durationSec = Math.max(1, ChronoUnit.SECONDS.between(from, to));

        // 모니터링 설정 조회 (없으면 Fallback: MIN_REQ_PER_MINUTE=1, ERROR_RATE_THRESHOLD=5.0, AVAILABILITY_SLO_TARGET=99.9)
        Map<String, String> config = getMonitoringConfigMap(tenantId);
        int minReqPerMinute = parseMinReqPerMinute(config.get(CODE_MIN_REQ_PER_MINUTE));
        double errorRateThreshold = parseErrorRateThreshold(config.get(CODE_ERROR_RATE_THRESHOLD));
        double sloTargetSuccessRate = parseAvailabilitySloTarget(config.get(CODE_AVAILABILITY_SLO_TARGET));
        double criticalThreshold = parseAvailabilityCriticalThreshold(config.get(CODE_AVAILABILITY_CRITICAL_THRESHOLD));
        long latencySloTarget = parseLatencySloTarget(config.get(CODE_LATENCY_SLO_TARGET));
        long latencyCriticalThreshold = parseLatencyCriticalThreshold(config.get(CODE_LATENCY_CRITICAL_THRESHOLD));
        double trafficSloTarget = parseTrafficSloTarget(config.get(CODE_TRAFFIC_SLO_TARGET));
        double trafficCriticalThreshold = parseTrafficCriticalThreshold(config.get(CODE_TRAFFIC_CRITICAL_THRESHOLD));
        int trafficPeakWindowSeconds = parseTrafficPeakWindowSeconds(config.get(CODE_TRAFFIC_PEAK_WINDOW_SECONDS));

        // 1. Availability: 데이터 0건이어도 successRate=100.0, downtimeMinutes=0 명시 (프론트 - 방지)
        double successRate = totalCount > 0 ? Math.round((double) successCount / totalCount * 10000.0) / 100.0 : 100.0;
        long downtimeBuckets1Min = apiCallHistoryRepository.countDowntimeBuckets1Min(tenantId, from, to, minReqPerMinute, errorRateThreshold);
        int downtimeMinutes = totalCount > 0 ? (int) downtimeBuckets1Min : 0;

        long totalPeriodMinutes = ChronoUnit.MINUTES.between(from, to);
        long uptimeMinutes = Math.max(0L, totalPeriodMinutes - downtimeMinutes);

        List<MonitoringSummaryKpi.DowntimeInterval> downtimeIntervals = new ArrayList<>();
        List<Object[]> bucketStarts = apiCallHistoryRepository.findDowntimeBucketStarts(tenantId, from, to, minReqPerMinute, errorRateThreshold);
        Set<Long> downtimeEpochSeconds = new HashSet<>();
        for (Object[] row : bucketStarts) {
            if (row != null && row.length > 0 && row[0] != null) {
                Instant start = row[0] instanceof Timestamp ? ((Timestamp) row[0]).toInstant() : null;
                if (start != null) {
                    downtimeEpochSeconds.add(start.getEpochSecond());
                    Instant end = start.plusSeconds(60);
                    downtimeIntervals.add(MonitoringSummaryKpi.DowntimeInterval.builder()
                            .start(DateTimeFormatter.ISO_INSTANT.format(start))
                            .end(DateTimeFormatter.ISO_INSTANT.format(end))
                            .build());
                }
            }
        }

        // Health Dots용 statusHistory: 기간을 버킷별로 나눠 UP|WARNING|DOWN|NO_DATA 및 가용성(%)
        int statusHistoryBucketSeconds = resolveStatusHistoryBucketSeconds(durationSec);
        List<MonitoringSummaryKpi.StatusHistoryItem> statusHistory = buildStatusHistory(
                tenantId, from, to, statusHistoryBucketSeconds, downtimeEpochSeconds, sloTargetSuccessRate);

        MonitoringSummaryKpi.TopCause topCause = null;
        List<TopCauseView> top5xx = apiCallHistoryRepository.findTop5xxPath(tenantId, from, to);
        if (!top5xx.isEmpty()) {
            TopCauseView v = top5xx.get(0);
            topCause = MonitoringSummaryKpi.TopCause.builder()
                    .path(v.getPath())
                    .statusGroup("5xx")
                    .count(v.getCount() != null ? v.getCount() : 0L)
                    .build();
        }

        // 지연시간: 데이터 없으면 0 반환 (프론트 - 표시 방지)
        long p50Ms = 0L;
        long p95Ms = 0L;
        long p99Ms = 0L;
        LatencyPercentilesView percentiles = apiCallHistoryRepository.findLatencyPercentiles(tenantId, from, to);
        if (percentiles != null) {
            p50Ms = toLongMsOrZero(percentiles.getP50Ms());
            p95Ms = toLongMsOrZero(percentiles.getP95Ms());
            p99Ms = toLongMsOrZero(percentiles.getP99Ms());
        }
        Double avgLatencyDouble = apiCallHistoryRepository.findAvgLatencyMs(tenantId, from, to);
        long avgLatency = avgLatencyDouble == null ? 0L : avgLatencyDouble.longValue();

        MonitoringSummaryKpi.TopSlow topSlow = null;
        List<TopSlowView> topSlowList = apiCallHistoryRepository.findTopSlowPath(tenantId, from, to);
        if (!topSlowList.isEmpty()) {
            TopSlowView v = topSlowList.get(0);
            topSlow = MonitoringSummaryKpi.TopSlow.builder()
                    .path(v.getPath())
                    .p95Ms(toLongMs(v.getP95Ms()))
                    .build();
        }

        // RPS: rpsPeak = (조회 기간 내 window초 버킷별 요청 수 중 최댓값) / TRAFFIC_PEAK_WINDOW_SECONDS. 기본 60초=1분.
        int windowSec = Math.max(1, trafficPeakWindowSeconds);
        Long maxInWindow = apiCallHistoryRepository.findMaxCountInWindow(tenantId, from, to, windowSec);
        double rpsPeakRaw = maxInWindow != null && windowSec > 0 ? (double) maxInWindow / windowSec : 0.0;
        double rpsAvgRaw = durationSec > 0 ? (double) requestCount / durationSec : 0.0;
        double rpsAvg = Math.round(rpsAvgRaw * 100.0) / 100.0;
        double rpsPeak = Math.round(rpsPeakRaw * 100.0) / 100.0;
        long totalUv = apiCallHistoryRepository.countDistinctClientsByTenantIdAndCreatedAtBetween(tenantId, from, to);

        MonitoringSummaryKpi.TopTraffic topTraffic = null;
        List<TopTrafficView> topTrafficList = apiCallHistoryRepository.findTopTrafficPath(tenantId, from, to);
        if (!topTrafficList.isEmpty()) {
            TopTrafficView v = topTrafficList.get(0);
            topTraffic = MonitoringSummaryKpi.TopTraffic.builder()
                    .path(v.getPath())
                    .requestCount(v.getRequestCount() != null ? v.getRequestCount() : 0L)
                    .build();
        }

        // 에러 분리 집계: 전체 요청 대비 4xx/5xx 비율(%) 및 절대 건수
        double rate4xx = totalCount > 0 ? Math.round((double) count4xx / totalCount * 10000.0) / 100.0 : 0.0;
        double rate5xx = totalCount > 0 ? Math.round((double) count5xx / totalCount * 10000.0) / 100.0 : 0.0;

        // Error Budget: ERROR_RATE_SLO_TARGET(목표 에러율 %, 기본 0.5) 기준. 소진율 = min(rate5xx/target, 1.0), burnRate = rate5xx/target
        double errorRateSloTarget = parseErrorRateSloTarget(config.get(CODE_ERROR_RATE_SLO_TARGET));
        double consumedRatioRaw = errorRateSloTarget > 0 ? rate5xx / errorRateSloTarget : 0.0;
        double consumedRatio = Math.min(Math.round(consumedRatioRaw * 100.0) / 100.0, 1.0);
        double burnRate = errorRateSloTarget > 0 ? Math.round(rate5xx / errorRateSloTarget * 100.0) / 100.0 : 0.0;
        double errorBudgetRemaining = Math.max(0.0, Math.round((1.0 - consumedRatio) * 10000.0) / 100.0);
        double errorBudgetSloTargetSuccessRate = Math.round((100.0 - errorRateSloTarget) * 100.0) / 100.0;
        String budgetPeriod = resolveBudgetPeriod(durationSec);
        MonitoringSummaryKpi.ErrorBudget budget = MonitoringSummaryKpi.ErrorBudget.builder()
                .period(budgetPeriod)
                .sloTargetSuccessRate(errorBudgetSloTargetSuccessRate)
                .consumedRatio(consumedRatio)
                .build();

        MonitoringSummaryKpi.ErrorCounts errorCounts = MonitoringSummaryKpi.ErrorCounts.builder()
                .count4xx(count4xx)
                .count5xx(count5xx)
                .build();

        // Top Error Path: 해당 기간 가장 많이 발생한 에러 1건 (path, statusCode, count)
        MonitoringSummaryKpi.TopError topError = null;
        List<TopErrorView> topErrorList = apiCallHistoryRepository.findTopErrorPathAndStatus(tenantId, from, to);
        if (!topErrorList.isEmpty()) {
            TopErrorView v = topErrorList.get(0);
            topError = MonitoringSummaryKpi.TopError.builder()
                    .path(v.getPath())
                    .statusCode(v.getStatusCode())
                    .count(v.getCount() != null ? v.getCount() : 0L)
                    .build();
        }

        return MonitoringSummaryKpi.builder()
                .availability(MonitoringSummaryKpi.AvailabilityKpi.builder()
                        .successRate(Double.valueOf(successRate))
                        .sloTargetSuccessRate(sloTargetSuccessRate)
                        .criticalThreshold(criticalThreshold)
                        .successCount(successCount)
                        .totalCount(totalCount)
                        .downtimeMinutes(Integer.valueOf(downtimeMinutes))
                        .uptimeMinutes(uptimeMinutes)
                        .downtimeIntervals(downtimeIntervals)
                        .statusHistory(statusHistory)
                        .topCause(topCause)
                        .build())
                .latency(MonitoringSummaryKpi.LatencyKpi.builder()
                        .avgLatency(avgLatency)
                        .p50Latency(p50Ms)
                        .p50Ms(p50Ms)
                        .p95Ms(p95Ms)
                        .p99Latency(p99Ms)
                        .p99Ms(p99Ms)
                        .sloTarget(latencySloTarget)
                        .criticalThreshold(latencyCriticalThreshold)
                        .topSlow(topSlow)
                        .build())
                .traffic(MonitoringSummaryKpi.TrafficKpi.builder()
                        .rpsAvg(rpsAvg)
                        .rpsPeak(rpsPeak)
                        .totalPv(requestCount)
                        .totalUv(totalUv)
                        .peakRps(rpsPeak)
                        .sloTarget(trafficSloTarget)
                        .criticalThreshold(trafficCriticalThreshold)
                        .requestCount(requestCount)
                        .pv(pv)
                        .uv(uv)
                        .topTraffic(topTraffic)
                        .build())
                .error(MonitoringSummaryKpi.ErrorKpi.builder()
                        .rate4xx(rate4xx)
                        .rate5xx(rate5xx)
                        .count4xx(count4xx)
                        .count5xx(count5xx)
                        .errorRate(rate5xx)
                        .errorCounts(errorCounts)
                        .errorBudgetRemaining(errorBudgetRemaining)
                        .burnRate(burnRate)
                        .budget(budget)
                        .topError(topError)
                        .build())
                .build();
    }

    /** 실시간 RPS: 최근 10초 평균(currentRps), 전일 동시간대 10초(prevRps). delta.rpsDeltaPercent 설정. attachDeltas 이후 호출. */
    private void attachTrafficRealtimeRps(Long tenantId, MonitoringSummaryKpi kpi) {
        if (kpi.getTraffic() == null) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tenSecAgo = now.minusSeconds(10);
        long countLast10 = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, tenSecAgo, now);
        double currentRps = Math.round(countLast10 / 10.0 * 100.0) / 100.0;
        LocalDateTime yesterdayTenSecAgo = now.minusDays(1).minusSeconds(10);
        LocalDateTime yesterdayNow = now.minusDays(1);
        long countPrev10 = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, yesterdayTenSecAgo, yesterdayNow);
        double prevRps = Math.round(countPrev10 / 10.0 * 100.0) / 100.0;
        kpi.getTraffic().setCurrentRps(currentRps);
        kpi.getTraffic().setPrevRps(prevRps);
        // Load % = (currentRps / TRAFFIC_CRITICAL_THRESHOLD) × 100. 0 RPS이면 0%, 분모 0 방지로 1.0 사용. 소수점 없이 정수 반올림.
        double critical = kpi.getTraffic().getCriticalThreshold() != null && kpi.getTraffic().getCriticalThreshold() > 0
                ? kpi.getTraffic().getCriticalThreshold() : 1.0;
        double loadPercentage = critical <= 0 ? 0.0 : (double) Math.round((currentRps / critical) * 100.0);
        kpi.getTraffic().setLoadPercentage(loadPercentage);
        double rpsDeltaPercent = prevRps > 0 ? Math.round((currentRps - prevRps) / prevRps * 10000.0) / 100.0 : (currentRps > 0 ? 100.0 : 0.0);
        MonitoringSummaryKpi.DeltaTraffic d = kpi.getTraffic().getDelta();
        kpi.getTraffic().setDelta(MonitoringSummaryKpi.DeltaTraffic.builder()
                .rpsAvg(d != null ? d.getRpsAvg() : null)
                .requestCount(d != null ? d.getRequestCount() : null)
                .pv(d != null ? d.getPv() : null)
                .uv(d != null ? d.getUv() : null)
                .pvDeltaPercent(d != null ? d.getPvDeltaPercent() : null)
                .uvDeltaPercent(d != null ? d.getUvDeltaPercent() : null)
                .rpsDeltaPercent(rpsDeltaPercent)
                .build());
    }

    private void attachDeltas(MonitoringSummaryKpi current, MonitoringSummaryKpi compare) {
        if (compare.getAvailability() != null && current.getAvailability() != null) {
            double cs = current.getAvailability().getSuccessRate() != null ? current.getAvailability().getSuccessRate() : 0;
            double ps = compare.getAvailability().getSuccessRate() != null ? compare.getAvailability().getSuccessRate() : 0;
            int cd = current.getAvailability().getDowntimeMinutes() != null ? current.getAvailability().getDowntimeMinutes() : 0;
            int pd = compare.getAvailability().getDowntimeMinutes() != null ? compare.getAvailability().getDowntimeMinutes() : 0;
            current.getAvailability().setDelta(MonitoringSummaryKpi.DeltaAvailability.builder()
                    .successRatePp(Math.round((cs - ps) * 100.0) / 100.0)
                    .downtimeMinutes(cd - pd)
                    .build());
        }
        if (compare.getLatency() != null && current.getLatency() != null) {
            long cp95 = current.getLatency().getP95Ms() != null ? current.getLatency().getP95Ms() : 0;
            long pp95 = compare.getLatency().getP95Ms() != null ? compare.getLatency().getP95Ms() : 0;
            long cp99 = current.getLatency().getP99Ms() != null ? current.getLatency().getP99Ms() : 0;
            long pp99 = compare.getLatency().getP99Ms() != null ? compare.getLatency().getP99Ms() : 0;
            current.getLatency().setDelta(MonitoringSummaryKpi.DeltaLatency.builder()
                    .p95Ms(cp95 - pp95)
                    .p99Ms(cp99 - pp99)
                    .build());
        }
        if (compare.getTraffic() != null && current.getTraffic() != null) {
            double crps = current.getTraffic().getRpsAvg() != null ? current.getTraffic().getRpsAvg() : 0;
            double prps = compare.getTraffic().getRpsAvg() != null ? compare.getTraffic().getRpsAvg() : 0;
            long crc = current.getTraffic().getRequestCount() != null ? current.getTraffic().getRequestCount() : 0;
            long prc = compare.getTraffic().getRequestCount() != null ? compare.getTraffic().getRequestCount() : 0;
            long cpv = current.getTraffic().getPv() != null ? current.getTraffic().getPv() : 0;
            long ppv = compare.getTraffic().getPv() != null ? compare.getTraffic().getPv() : 0;
            long cuv = current.getTraffic().getUv() != null ? current.getTraffic().getUv() : 0;
            long puv = compare.getTraffic().getUv() != null ? compare.getTraffic().getUv() : 0;
            double pvDeltaPct = calculateDelta(cpv, ppv);
            double uvDeltaPct = calculateDelta(cuv, puv);
            current.getTraffic().setDelta(MonitoringSummaryKpi.DeltaTraffic.builder()
                    .rpsAvg(Math.round((crps - prps) * 100.0) / 100.0)
                    .requestCount(crc - prc)
                    .pv(cpv - ppv)
                    .uv(cuv - puv)
                    .pvDeltaPercent(Math.round(pvDeltaPct * 100.0) / 100.0)
                    .uvDeltaPercent(Math.round(uvDeltaPct * 100.0) / 100.0)
                    .build());
        }
        // Error Delta: 이전 동일 기간 대비 rate5xx 퍼센트포인트(pP) 증감, count5xx 증감
        if (compare.getError() != null && current.getError() != null) {
            double cr5 = current.getError().getRate5xx() != null ? current.getError().getRate5xx() : 0;
            double pr5 = compare.getError().getRate5xx() != null ? compare.getError().getRate5xx() : 0;
            long cc5 = current.getError().getCount5xx() != null ? current.getError().getCount5xx() : 0;
            long pc5 = compare.getError().getCount5xx() != null ? compare.getError().getCount5xx() : 0;
            current.getError().setDelta(MonitoringSummaryKpi.DeltaError.builder()
                    .rate5xxPp(Math.round((cr5 - pr5) * 100.0) / 100.0)
                    .count5xx(cc5 - pc5)
                    .build());
        }
    }

    /** Projection percentile/slow 값 Double → Long(ms) 변환 (null이면 null) */
    private static Long toLongMs(Double v) {
        return v == null ? null : Long.valueOf(v.longValue());
    }

    /** Projection 값 Double → Long(ms), null이면 0 (수치 필드 null 방지) */
    private static long toLongMsOrZero(Double v) {
        return v == null ? 0L : v.longValue();
    }

    private double calculateDelta(double current, double previous) {
        if (previous == 0) return current > 0 ? 100.0 : 0.0;
        return ((current - previous) / previous) * 100;
    }

    /** 조회 기간(초)으로 Error Budget period 라벨 (1H, 24H, 7D, WEEK) */
    private static String resolveBudgetPeriod(long durationSec) {
        if (durationSec <= 3600L) return "1H";
        if (durationSec <= 86400L) return "24H";
        if (durationSec <= 604800L) return "7D";
        return "WEEK";
    }

    /**
     * Health Dots 버킷 크기(초) 결정:
     * - 1h 이하: 2분 (120초)  → 약 30개 도트
     * - 3h 이하: 5분 (300초) → 최대 약 36개 도트
     * - 6h 이하: 10분(600초) → 최대 약 36개 도트
     * - 24h 이하: 30분(1800초) → 최대 48개 도트
     * - 7일 이하: 6시간(21600초) → 최대 28개 도트
     * - 그 외: 24시간(86400초) → 30일 기준 30개 도트
     *
     * 조회 기간이 1~6시간 사이인 경우 도트 개수가 최소 30개 내외가 되도록,
     * 버킷 크기를 2~10분 사이에서 유동적으로 조정한다.
     */
    private static int resolveStatusHistoryBucketSeconds(long durationSec) {
        if (durationSec <= 3600L) return 120;        // 1h 이내: 2분 버킷 → ~30 도트
        if (durationSec <= 3 * 3600L) return 300;    // 3h 이내: 5분 버킷 → 최대 ~36 도트
        if (durationSec <= 6 * 3600L) return 600;    // 6h 이내: 10분 버킷 → 최대 ~36 도트
        if (durationSec <= 86400L) return 1800;      // 24h 이내: 30분 버킷 → 최대 48 도트
        if (durationSec <= 604800L) return 21600;    // 7일 이내: 6시간 버킷 → 최대 28 도트
        return 86400;                                // 그 외: 24시간(1일) 버킷 → 30일 기준 30 도트
    }

    /** Health Dots용 statusHistory: 버킷별 UP|WARNING|DOWN|NO_DATA 및 availability(%) */
    private List<MonitoringSummaryKpi.StatusHistoryItem> buildStatusHistory(
            Long tenantId, LocalDateTime from, LocalDateTime to, int bucketSeconds,
            Set<Long> downtimeEpochSeconds, double sloTargetSuccessRate) {
        List<MonitoringSummaryKpi.StatusHistoryItem> out = new ArrayList<>();
        long fromEpoch = from.atOffset(ZoneOffset.UTC).toEpochSecond();
        long toEpoch = to.atOffset(ZoneOffset.UTC).toEpochSecond();
        long startBucket = (fromEpoch / bucketSeconds) * bucketSeconds;

        List<Object[]> rows = apiCallHistoryRepository.findAvailabilityBucketStats(tenantId, from, to, bucketSeconds);
        Map<Long, long[]> statsMap = new HashMap<>();
        for (Object[] row : rows) {
            if (row != null && row.length >= 3 && row[0] != null) {
                Instant inst = row[0] instanceof Timestamp ? ((Timestamp) row[0]).toInstant() : null;
                if (inst != null) {
                    long bucketEpoch = inst.getEpochSecond();
                    long total = row[1] instanceof Number ? ((Number) row[1]).longValue() : 0L;
                    long success = row[2] instanceof Number ? ((Number) row[2]).longValue() : 0L;
                    statsMap.put(bucketEpoch, new long[]{total, success});
                }
            }
        }

        for (long b = startBucket; b < toEpoch; b += bucketSeconds) {
            final long bucketStart = b;
            final long bucketEnd = b + bucketSeconds;
            long[] pair = statsMap.getOrDefault(bucketStart, new long[]{0L, 0L});
            long total = pair[0];
            long success = pair[1];
            double availability = total > 0 ? Math.round((double) success / total * 10000.0) / 100.0 : 0.0;
            boolean isDown = downtimeEpochSeconds.stream().anyMatch(d -> d >= bucketStart && d < bucketEnd);
            String status = total == 0 ? "NO_DATA" : (isDown ? "DOWN" : (availability < sloTargetSuccessRate ? "WARNING" : "UP"));
            String timestamp = Instant.ofEpochSecond(bucketStart).toString();
            out.add(MonitoringSummaryKpi.StatusHistoryItem.builder()
                    .timestamp(timestamp)
                    .status(status)
                    .availability(availability)
                    .build());
        }
        return out;
    }

    /** 테넌트별 모니터링 설정 조회 (키=config_key 코드, 값=config_value). 없으면 기본값 맵 반환 */
    private Map<String, String> getMonitoringConfigMap(Long tenantId) {
        Map<String, String> map = new HashMap<>();
        map.put(CODE_MIN_REQ_PER_MINUTE, String.valueOf(DEFAULT_MIN_REQ_PER_MINUTE));
        map.put(CODE_ERROR_RATE_THRESHOLD, String.valueOf(DEFAULT_ERROR_RATE_THRESHOLD));
        map.put(CODE_AVAILABILITY_SLO_TARGET, String.valueOf(DEFAULT_AVAILABILITY_SLO_TARGET));
        map.put(CODE_AVAILABILITY_CRITICAL_THRESHOLD, String.valueOf(DEFAULT_AVAILABILITY_CRITICAL_THRESHOLD));
        map.put(CODE_LATENCY_SLO_TARGET, String.valueOf(DEFAULT_LATENCY_SLO_TARGET));
        map.put(CODE_LATENCY_CRITICAL_THRESHOLD, String.valueOf(DEFAULT_LATENCY_CRITICAL_THRESHOLD));
        map.put(CODE_TRAFFIC_SLO_TARGET, String.valueOf(DEFAULT_TRAFFIC_SLO_TARGET));
        map.put(CODE_TRAFFIC_CRITICAL_THRESHOLD, String.valueOf(DEFAULT_TRAFFIC_CRITICAL_THRESHOLD));
        map.put(CODE_TRAFFIC_PEAK_WINDOW_SECONDS, String.valueOf(DEFAULT_TRAFFIC_PEAK_WINDOW_SECONDS));
        map.put(CODE_ERROR_RATE_SLO_TARGET, String.valueOf(DEFAULT_ERROR_RATE_SLO_TARGET));
        map.put(CODE_ERROR_BUDGET_TOTAL, String.valueOf(DEFAULT_ERROR_BUDGET_TOTAL));
        List<com.dwp.services.auth.entity.MonitoringConfig> list = monitoringConfigRepository.findByTenantIdOrderByMonitoringConfigId(tenantId);
        for (com.dwp.services.auth.entity.MonitoringConfig c : list) {
            if (c.getConfigKey() != null && c.getConfigValue() != null) {
                map.put(c.getConfigKey(), c.getConfigValue());
            }
        }
        return map;
    }

    private static int parseMinReqPerMinute(String value) {
        if (value == null || value.isBlank()) return DEFAULT_MIN_REQ_PER_MINUTE;
        try {
            int v = Integer.parseInt(value.trim());
            return v >= 0 ? v : DEFAULT_MIN_REQ_PER_MINUTE;
        } catch (NumberFormatException e) {
            return DEFAULT_MIN_REQ_PER_MINUTE;
        }
    }

    private static double parseErrorRateThreshold(String value) {
        if (value == null || value.isBlank()) return DEFAULT_ERROR_RATE_THRESHOLD;
        try {
            double v = Double.parseDouble(value.trim());
            return v >= 0 ? v : DEFAULT_ERROR_RATE_THRESHOLD;
        } catch (NumberFormatException e) {
            return DEFAULT_ERROR_RATE_THRESHOLD;
        }
    }

    private static double parseAvailabilitySloTarget(String value) {
        if (value == null || value.isBlank()) return DEFAULT_AVAILABILITY_SLO_TARGET;
        try {
            double v = Double.parseDouble(value.trim());
            return v >= 0 && v <= 100 ? v : DEFAULT_AVAILABILITY_SLO_TARGET;
        } catch (NumberFormatException e) {
            return DEFAULT_AVAILABILITY_SLO_TARGET;
        }
    }

    private static double parseAvailabilityCriticalThreshold(String value) {
        if (value == null || value.isBlank()) return DEFAULT_AVAILABILITY_CRITICAL_THRESHOLD;
        try {
            double v = Double.parseDouble(value.trim());
            return v >= 0 && v <= 100 ? v : DEFAULT_AVAILABILITY_CRITICAL_THRESHOLD;
        } catch (NumberFormatException e) {
            return DEFAULT_AVAILABILITY_CRITICAL_THRESHOLD;
        }
    }

    private static long parseLatencySloTarget(String value) {
        if (value == null || value.isBlank()) return DEFAULT_LATENCY_SLO_TARGET;
        try {
            long v = Long.parseLong(value.trim());
            return v >= 0 ? v : DEFAULT_LATENCY_SLO_TARGET;
        } catch (NumberFormatException e) {
            return DEFAULT_LATENCY_SLO_TARGET;
        }
    }

    private static long parseLatencyCriticalThreshold(String value) {
        if (value == null || value.isBlank()) return DEFAULT_LATENCY_CRITICAL_THRESHOLD;
        try {
            long v = Long.parseLong(value.trim());
            return v >= 0 ? v : DEFAULT_LATENCY_CRITICAL_THRESHOLD;
        } catch (NumberFormatException e) {
            return DEFAULT_LATENCY_CRITICAL_THRESHOLD;
        }
    }

    private static double parseTrafficSloTarget(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TRAFFIC_SLO_TARGET;
        try {
            double v = Double.parseDouble(value.trim());
            return v >= 0 ? v : DEFAULT_TRAFFIC_SLO_TARGET;
        } catch (NumberFormatException e) {
            return DEFAULT_TRAFFIC_SLO_TARGET;
        }
    }

    private static double parseTrafficCriticalThreshold(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TRAFFIC_CRITICAL_THRESHOLD;
        try {
            double v = Double.parseDouble(value.trim());
            return v >= 0 ? v : DEFAULT_TRAFFIC_CRITICAL_THRESHOLD;
        } catch (NumberFormatException e) {
            return DEFAULT_TRAFFIC_CRITICAL_THRESHOLD;
        }
    }

    private static int parseTrafficPeakWindowSeconds(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TRAFFIC_PEAK_WINDOW_SECONDS;
        try {
            int v = Integer.parseInt(value.trim());
            return v >= 1 && v <= 86400 ? v : DEFAULT_TRAFFIC_PEAK_WINDOW_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TRAFFIC_PEAK_WINDOW_SECONDS;
        }
    }

    private static double parseErrorRateSloTarget(String value) {
        if (value == null || value.isBlank()) return DEFAULT_ERROR_RATE_SLO_TARGET;
        try {
            double v = Double.parseDouble(value.trim());
            return v > 0 && v <= 100 ? v : DEFAULT_ERROR_RATE_SLO_TARGET;
        } catch (NumberFormatException e) {
            return DEFAULT_ERROR_RATE_SLO_TARGET;
        }
    }

    @SuppressWarnings("unused") // ERROR_BUDGET_TOTAL: 표시/스케일 용도로 향후 사용 예정
    private static double parseErrorBudgetTotal(String value) {
        if (value == null || value.isBlank()) return DEFAULT_ERROR_BUDGET_TOTAL;
        try {
            double v = Double.parseDouble(value.trim());
            return v >= 0 ? v : DEFAULT_ERROR_BUDGET_TOTAL;
        } catch (NumberFormatException e) {
            return DEFAULT_ERROR_BUDGET_TOTAL;
        }
    }

    @Transactional(readOnly = true)
    public Page<PageViewEvent> getPageViews(Long tenantId, LocalDateTime from, LocalDateTime to,
                                            String keyword, String route, String menu, String path,
                                            Long userId, Pageable pageable) {
        // 빈 문자열을 null로 변환
        keyword = (keyword != null && keyword.trim().isEmpty()) ? null : keyword;
        route = (route != null && route.trim().isEmpty()) ? null : route;
        menu = (menu != null && menu.trim().isEmpty()) ? null : menu;
        path = (path != null && path.trim().isEmpty()) ? null : path;
        
        log.debug("getPageViews 서비스: tenantId={}, from={}, to={}, keyword={}, path={}, route={}, menu={}, userId={}", 
                tenantId, from, to, keyword, path, route, menu, userId);
        
        Page<PageViewEvent> result;
        if (from != null && to != null) {
            result = pageViewEventRepository.findByTenantIdAndFiltersWithDate(
                    tenantId, from, to, keyword, route, menu, path, userId, pageable);
        } else {
            result = pageViewEventRepository.findByTenantIdAndFiltersWithoutDate(
                    tenantId, keyword, route, menu, path, userId, pageable);
        }
        
        log.debug("getPageViews 쿼리 결과: totalElements={}, contentSize={}", 
                result.getTotalElements(), result.getContent().size());
        
        return result;
    }

    @Transactional(readOnly = true)
    public Page<ApiCallHistory> getApiHistories(Long tenantId, LocalDateTime from, LocalDateTime to,
                                                 String keyword, String apiName, String apiUrl,
                                                 Integer statusCode, Long userId,
                                                 String statusGroup, String pathLike, Long minLatencyMs, Long maxLatencyMs,
                                                 String sort, Pageable pageable) {
        keyword = (keyword != null && keyword.trim().isEmpty()) ? null : keyword;
        apiName = (apiName != null && apiName.trim().isEmpty()) ? null : apiName;
        apiUrl = (apiUrl != null && apiUrl.trim().isEmpty()) ? null : apiUrl;
        pathLike = (pathLike != null && pathLike.trim().isEmpty()) ? null : pathLike;
        statusGroup = (statusGroup != null && statusGroup.trim().isEmpty()) ? null : statusGroup;
        if ("COUNT_DESC".equals(sort)) {
            log.warn("api-histories: COUNT_DESC requested but path-aggregation endpoint not implemented; using TIME_DESC");
            sort = "TIME_DESC";
        }
        Sort order = "LATENCY_DESC".equals(sort)
                ? Sort.by(Sort.Direction.DESC, "latencyMs")
                : Sort.by(Sort.Direction.DESC, "createdAt");
        Pageable pageableWithSort = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), order);

        if (from != null && to != null) {
            boolean useDrillDown = statusGroup != null || pathLike != null || minLatencyMs != null || maxLatencyMs != null;
            if (useDrillDown)
                return apiCallHistoryRepository.findByTenantIdAndFiltersWithDateAndDrillDown(
                        tenantId, from, to, keyword, apiName, apiUrl, pathLike, statusCode, statusGroup,
                        minLatencyMs, maxLatencyMs, userId, pageableWithSort);
            return apiCallHistoryRepository.findByTenantIdAndFiltersWithDate(
                    tenantId, from, to, keyword, apiName, apiUrl, statusCode, userId, pageableWithSort);
        }
        return apiCallHistoryRepository.findByTenantIdAndFiltersWithoutDate(
                tenantId, keyword, apiName, apiUrl, statusCode, userId, pageable);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata to JSON", e);
            return null;
        }
    }
}
