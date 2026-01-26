package com.dwp.services.auth.service.monitoring;

import com.dwp.services.auth.dto.monitoring.EventLogItem;
import com.dwp.services.auth.dto.monitoring.TimeseriesResponse;
import com.dwp.services.auth.dto.monitoring.VisitorSummary;
import com.dwp.services.auth.entity.monitoring.EventLog;
import com.dwp.services.auth.repository.ApiCallHistoryRepository;
import com.dwp.services.auth.repository.PageViewEventRepository;
import com.dwp.services.auth.repository.PageViewDailyStatRepository;
import com.dwp.services.auth.repository.monitoring.EventLogRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Admin 모니터링 조회 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMonitoringService {
    
    private final PageViewEventRepository pageViewEventRepository;
    private final EventLogRepository eventLogRepository;
    private final ApiCallHistoryRepository apiCallHistoryRepository;
    private final PageViewDailyStatRepository pageViewDailyStatRepository;
    
    /**
     * 방문자 목록 조회
     */
    @Transactional(readOnly = true)
    public Page<VisitorSummary> getVisitors(Long tenantId, LocalDateTime from, LocalDateTime to, 
                                             String keyword, Pageable pageable) {
        List<Object[]> results;
        if (keyword != null && !keyword.trim().isEmpty()) {
            results = pageViewEventRepository.findVisitorSummariesByTenantIdAndKeyword(
                    tenantId, from, to, keyword.trim());
        } else {
            results = pageViewEventRepository.findVisitorSummariesByTenantIdAndCreatedAtBetween(
                    tenantId, from, to);
        }
        
        // EventLog에서 eventCount 집계
        List<VisitorSummary> summaries = results.stream().map(row -> {
            String visitorId = (String) row[0];
            LocalDateTime firstSeen = (LocalDateTime) row[1];
            LocalDateTime lastSeen = (LocalDateTime) row[2];
            Long pvCount = ((Number) row[3]).longValue();
            String lastPath = (String) row[4];
            
            // visitorId가 null이면 "anonymous"로 매핑
            String displayVisitorId = visitorId != null ? visitorId : "anonymous";
            
            // EventLog에서 해당 visitorId의 eventCount 조회
            Long eventCount = eventLogRepository.countByTenantIdAndOccurredAtBetween(
                    tenantId, from, to);
            // TODO: visitorId별 eventCount 집계는 향후 개선 (현재는 전체 이벤트 수)
            
            return VisitorSummary.builder()
                    .visitorId(displayVisitorId)
                    .firstSeenAt(firstSeen)
                    .lastSeenAt(lastSeen)
                    .pageViewCount(pvCount)
                    .eventCount(eventCount)
                    .lastPath(lastPath)
                    .build();
        }).collect(Collectors.toList());
        
        // 페이징 처리
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), summaries.size());
        List<VisitorSummary> pagedSummaries = start < summaries.size() 
                ? summaries.subList(start, end) 
                : new ArrayList<>();
        
        @SuppressWarnings("null")
        Page<VisitorSummary> result = new PageImpl<>(pagedSummaries, pageable, summaries.size());
        return result;
    }
    
    /**
     * 이벤트 로그 목록 조회
     */
    @Transactional(readOnly = true)
    public Page<EventLogItem> getEvents(Long tenantId, LocalDateTime from, LocalDateTime to,
                                         String eventType, String resourceKey, String keyword,
                                         Pageable pageable) {
        Page<EventLog> eventLogs;
        if (keyword != null && !keyword.trim().isEmpty()) {
            eventLogs = eventLogRepository.findByTenantIdAndFiltersWithKeyword(
                    tenantId, from, to, eventType, resourceKey, keyword.trim(), pageable);
        } else {
            eventLogs = eventLogRepository.findByTenantIdAndFilters(
                    tenantId, from, to, eventType, resourceKey, pageable);
        }
        
        return eventLogs.map(this::toEventLogItem);
    }
    
    /** API 버킷 기반 시계열 메트릭 (sys_api_call_histories). LATENCY_P95 등은 여기서만 처리하며 API_TOTAL로 폴백하지 않음. */
    private static final List<String> API_BUCKET_METRICS = List.of(
            "RPS", "API_4XX", "API_5XX", "LATENCY_P50", "LATENCY_P95", "LATENCY_P99", "AVAILABILITY");

    /** interval 파라미터: 1m, 5m, 1h, 1d (대소문자 무시). 기존 HOUR/DAY는 1h/1d로 정규화. */
    private static final DateTimeFormatter LABEL_FMT_HOUR_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter LABEL_FMT_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 시계열 응답 캐시: 동일 (tenant, from, to, interval, metric) 재요청 시 DB 전수 조회 방지. TTL 1분, 최대 500건. */
    private final Cache<String, TimeseriesResponse> timeseriesCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    /**
     * 시계열 데이터 조회.
     * <ul>
     *   <li><b>동적 그룹화</b>: 요청 interval(1m, 5m, 1h, 1d)을 파싱하여 SQL GROUP BY 시간 단위로 적용. (규격: docs/api-spec/ADMIN_MONITORING_TIMESERIES_INTERVAL_SPEC.md)</li>
     *   <li><b>P95 정확성</b>: 각 구간 내 전체 모수에서 percentile_cont(0.95)로 p95 산출. 단순 평균(AVG) 사용 금지.</li>
     *   <li><b>빈 구간 처리</b>: 데이터 없는 구간은 null 대신 0 또는 직전 구간 값으로 채워 차트 선이 끊기지 않게 함.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public TimeseriesResponse getTimeseries(Long tenantId, LocalDateTime from, LocalDateTime to,
                                             String interval, String metric) {
        String normalizedInterval = normalizeInterval(interval);
        String cacheKey = tenantId + "|" + from + "|" + to + "|" + normalizedInterval + "|" + metric;
        TimeseriesResponse cached = timeseriesCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        long bucketSeconds = bucketSecondsFor(normalizedInterval);

        if (API_BUCKET_METRICS.contains(metric)) {
            fillTimeseriesFromApiBuckets(tenantId, from, to, normalizedInterval, metric, bucketSeconds, labels, values);
            if ("LATENCY_P50".equals(metric) || "LATENCY_P95".equals(metric) || "LATENCY_P99".equals(metric)) {
                fillLatencyGaps(values);
            }
        } else if ("1d".equals(normalizedInterval)) {
            LocalDate startDate = from.toLocalDate();
            LocalDate endDate = to.toLocalDate();
            LocalDate currentDate = startDate;
            while (!currentDate.isAfter(endDate)) {
                final LocalDate date = currentDate;
                labels.add(date.format(LABEL_FMT_DATE));
                long value = switch (metric) {
                    case "PV" -> pageViewDailyStatRepository.findByTenantIdAndStatDateBetween(tenantId, date, date)
                            .stream().mapToLong(s -> s.getPvCount() != null ? s.getPvCount() : 0L).sum();
                    case "UV" -> pageViewDailyStatRepository.findByTenantIdAndStatDateBetween(tenantId, date, date)
                            .stream().mapToLong(s -> s.getUvCount() != null ? s.getUvCount() : 0L).sum();
                    case "EVENT" -> {
                        LocalDateTime dayStart = date.atStartOfDay();
                        LocalDateTime dayEnd = date.atTime(23, 59, 59);
                        yield eventLogRepository.countByTenantIdAndOccurredAtBetween(tenantId, dayStart, dayEnd);
                    }
                    case "API_TOTAL" -> {
                        LocalDateTime dayStart = date.atStartOfDay();
                        LocalDateTime dayEnd = date.atTime(23, 59, 59);
                        yield apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, dayStart, dayEnd);
                    }
                    case "API_ERROR" -> {
                        LocalDateTime dayStart = date.atStartOfDay();
                        LocalDateTime dayEnd = date.atTime(23, 59, 59);
                        yield apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, dayStart, dayEnd);
                    }
                    default -> 0L;
                };
                values.add((double) value);
                currentDate = currentDate.plusDays(1);
            }
        } else {
            LocalDateTime current = from.truncatedTo(ChronoUnit.HOURS);
            while (!current.isAfter(to)) {
                labels.add(current.format(LABEL_FMT_HOUR_MIN));
                LocalDateTime nextHour = current.plusHours(1);
                long value = switch (metric) {
                    case "PV" -> pageViewEventRepository.countPvByTenantIdAndCreatedAtBetween(tenantId, current, nextHour);
                    case "UV" -> pageViewEventRepository.countUvByTenantIdAndCreatedAtBetween(tenantId, current, nextHour);
                    case "EVENT" -> eventLogRepository.countByTenantIdAndOccurredAtBetween(tenantId, current, nextHour);
                    case "API_TOTAL" -> apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, current, nextHour);
                    case "API_ERROR" -> apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, current, nextHour);
                    default -> 0L;
                };
                values.add((double) value);
                current = nextHour;
            }
        }

        TimeseriesResponse response = TimeseriesResponse.builder()
                .interval(normalizedInterval)
                .metric(metric)
                .labels(labels)
                .values(values)
                .build();
        timeseriesCache.put(cacheKey, response);
        return response;
    }

    /** 동적 그룹화: API interval 파라미터를 정규화(1m, 5m, 1h, 1d). HOUR→1h, DAY→1d. GROUP BY 단위 선택에 사용. */
    private static String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) return "1d";
        String s = interval.trim().toLowerCase();
        if ("hour".equals(s)) return "1h";
        if ("day".equals(s)) return "1d";
        return s;
    }

    /** 정규화된 interval에 대한 버킷 길이(초) */
    private static long bucketSecondsFor(String normalizedInterval) {
        return switch (normalizedInterval) {
            case "1m" -> 60L;
            case "5m" -> 300L;
            case "1h" -> 3600L;
            case "1d" -> 86400L;
            default -> "1d".equals(normalizedInterval) ? 86400L : 3600L;
        };
    }

    /** 빈 구간 처리(Zero-filling): 데이터 없는 구간에 null 대신 0 또는 직전 구간 값을 넣어 차트 선이 끊기지 않도록 함. */
    private static void fillLatencyGaps(List<Double> values) {
        double last = 0.0;
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i);
            if (Double.isNaN(v)) {
                values.set(i, last);
            } else {
                last = v;
            }
        }
    }

    /** 동적 그룹화: normalizedInterval(1m|5m|1h|1d)에 따라 해당 GROUP BY 쿼리 호출 후, from~to 모든 버킷에 대해 라벨·값 생성. */
    private void fillTimeseriesFromApiBuckets(Long tenantId, LocalDateTime from, LocalDateTime to,
                                               String normalizedInterval, String metric, long bucketSeconds,
                                               List<String> labels, List<Double> values) {
        List<Object[]> rows = switch (normalizedInterval) {
            case "1m" -> apiCallHistoryRepository.findTimeseriesBucketStatsMinute(tenantId, from, to);
            case "5m" -> apiCallHistoryRepository.findTimeseriesBucketStats5Min(tenantId, from, to);
            case "1d" -> apiCallHistoryRepository.findTimeseriesBucketStatsDay(tenantId, from, to);
            default -> apiCallHistoryRepository.findTimeseriesBucketStatsHour(tenantId, from, to);
        };
        DateTimeFormatter keyFmt = "1d".equals(normalizedInterval) ? LABEL_FMT_DATE : LABEL_FMT_HOUR_MIN;
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            Object ts = row[0];
            java.time.LocalDateTime ldt = ts instanceof java.sql.Timestamp ? ((java.sql.Timestamp) ts).toLocalDateTime()
                    : ts instanceof java.time.Instant ? java.time.LocalDateTime.ofInstant((java.time.Instant) ts, java.time.ZoneId.systemDefault()) : null;
            String key = ldt != null ? ("1d".equals(normalizedInterval) ? ldt.toLocalDate().format(keyFmt) : ldt.format(keyFmt)) : String.valueOf(ts);
            map.put(key, row);
        }
        if ("1d".equals(normalizedInterval)) {
            LocalDate startDate = from.toLocalDate();
            LocalDate endDate = to.toLocalDate();
            LocalDate current = startDate;
            while (!current.isAfter(endDate)) {
                String label = current.format(LABEL_FMT_DATE);
                labels.add(label);
                values.add(getBucketMetricValue(map, label, metric, bucketSeconds));
                current = current.plusDays(1);
            }
        } else {
            LocalDateTime current = alignToBucketStart(from, normalizedInterval);
            LocalDateTime end = to;
            while (!current.isAfter(end)) {
                String label = current.format(LABEL_FMT_HOUR_MIN);
                labels.add(label);
                values.add(getBucketMetricValue(map, label, metric, bucketSeconds));
                current = nextBucketStart(current, normalizedInterval);
            }
        }
    }

    private static LocalDateTime alignToBucketStart(LocalDateTime from, String normalizedInterval) {
        return switch (normalizedInterval) {
            case "1m" -> from.truncatedTo(ChronoUnit.MINUTES);
            case "5m" -> from.withMinute((from.getMinute() / 5) * 5).withSecond(0).withNano(0);
            case "1h" -> from.truncatedTo(ChronoUnit.HOURS);
            default -> from.truncatedTo(ChronoUnit.HOURS);
        };
    }

    private static LocalDateTime nextBucketStart(LocalDateTime current, String normalizedInterval) {
        return switch (normalizedInterval) {
            case "1m" -> current.plusMinutes(1);
            case "5m" -> current.plusMinutes(5);
            case "1h" -> current.plusHours(1);
            default -> current.plusHours(1);
        };
    }

    /**
     * 버킷별 메트릭 값 추출. LATENCY_P95는 버킷 내 percentile_cont(0.95) 결과만 사용(평균 아님, API_TOTAL 미사용).
     * 해당 버킷에 지연 데이터가 없으면 LATENCY_* 는 Double.NaN 반환.
     */
    private double getBucketMetricValue(Map<String, Object[]> map, String bucketKey, String metric, long bucketSeconds) {
        Object[] row = map.get(bucketKey);
        if (row == null || row[1] == null) {
            if ("LATENCY_P50".equals(metric) || "LATENCY_P95".equals(metric) || "LATENCY_P99".equals(metric))
                return Double.NaN;
            return 0.0;
        }
        long total = ((Number) row[1]).longValue();
        long count4xx = row[2] != null ? ((Number) row[2]).longValue() : 0;
        long count5xx = row[3] != null ? ((Number) row[3]).longValue() : 0;
        long countSuccess = row[4] != null ? ((Number) row[4]).longValue() : 0;
        Number p50 = row.length > 5 && row[5] != null ? (Number) row[5] : null;
        Number p95 = row.length > 6 && row[6] != null ? (Number) row[6] : null;
        Number p99 = row.length > 7 && row[7] != null ? (Number) row[7] : null;
        return switch (metric) {
            case "RPS" -> total > 0 ? Math.round((double) total / bucketSeconds * 100.0) / 100.0 : 0.0;
            case "API_4XX" -> (double) count4xx;
            case "API_5XX" -> (double) count5xx;
            case "AVAILABILITY" -> total > 0 ? Math.round((double) countSuccess / total * 10000.0) / 100.0 : 0.0;
            case "LATENCY_P50" -> p50 != null ? p50.doubleValue() : Double.NaN;
            case "LATENCY_P95" -> p95 != null ? p95.doubleValue() : Double.NaN;
            case "LATENCY_P99" -> p99 != null ? p99.doubleValue() : Double.NaN;
            default -> 0.0;
        };
    }
    
    private EventLogItem toEventLogItem(EventLog eventLog) {
        return EventLogItem.builder()
                .sysEventLogId(eventLog.getSysEventLogId())
                .occurredAt(eventLog.getOccurredAt())
                .eventType(eventLog.getEventType())
                .resourceKey(eventLog.getResourceKey())
                .action(eventLog.getAction())
                .label(eventLog.getLabel())
                .visitorId(eventLog.getVisitorId())
                .userId(eventLog.getUserId())
                .path(eventLog.getPath())
                .metadata(eventLog.getMetadata())
                .build();
    }
}
