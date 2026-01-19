package com.dwp.services.auth.service.monitoring;

import com.dwp.services.auth.dto.monitoring.EventLogItem;
import com.dwp.services.auth.dto.monitoring.TimeseriesResponse;
import com.dwp.services.auth.dto.monitoring.VisitorSummary;
import com.dwp.services.auth.entity.monitoring.EventLog;
import com.dwp.services.auth.repository.ApiCallHistoryRepository;
import com.dwp.services.auth.repository.PageViewEventRepository;
import com.dwp.services.auth.repository.PageViewDailyStatRepository;
import com.dwp.services.auth.repository.monitoring.EventLogRepository;
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
import java.util.ArrayList;
import java.util.List;
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
    
    /**
     * 시계열 데이터 조회
     */
    @Transactional(readOnly = true)
    public TimeseriesResponse getTimeseries(Long tenantId, LocalDateTime from, LocalDateTime to,
                                             String interval, String metric) {
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        
        if ("DAY".equals(interval)) {
            // 일별 집계 (sys_page_view_daily_stats 우선 사용)
            LocalDate startDate = from.toLocalDate();
            LocalDate endDate = to.toLocalDate();
            
            LocalDate currentDate = startDate;
            while (!currentDate.isAfter(endDate)) {
                final LocalDate date = currentDate; // effectively final
                labels.add(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
                
                long value = switch (metric) {
                    case "PV" -> {
                        List<com.dwp.services.auth.entity.PageViewDailyStat> stats = 
                                pageViewDailyStatRepository.findByTenantIdAndStatDateBetween(tenantId, date, date);
                        yield stats.stream()
                                .mapToLong(s -> s.getPvCount() != null ? s.getPvCount() : 0L)
                                .sum();
                    }
                    case "UV" -> {
                        List<com.dwp.services.auth.entity.PageViewDailyStat> stats = 
                                pageViewDailyStatRepository.findByTenantIdAndStatDateBetween(tenantId, date, date);
                        yield stats.stream()
                                .mapToLong(s -> s.getUvCount() != null ? s.getUvCount() : 0L)
                                .sum();
                    }
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
                values.add(value);
                currentDate = currentDate.plusDays(1);
            }
        } else {
            // 시간별 집계 (HOUR)
            LocalDateTime current = from;
            while (!current.isAfter(to)) {
                labels.add(current.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                LocalDateTime nextHour = current.plusHours(1);
                
                long value = switch (metric) {
                    case "PV" -> pageViewEventRepository.countPvByTenantIdAndCreatedAtBetween(
                            tenantId, current, nextHour);
                    case "UV" -> {
                        // UV는 distinct visitor_id count
                        long count = pageViewEventRepository.countUvByTenantIdAndCreatedAtBetween(
                                tenantId, current, nextHour);
                        yield count;
                    }
                    case "EVENT" -> eventLogRepository.countByTenantIdAndOccurredAtBetween(
                            tenantId, current, nextHour);
                    case "API_TOTAL" -> apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(
                            tenantId, current, nextHour);
                    case "API_ERROR" -> apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(
                            tenantId, current, nextHour);
                    default -> 0L;
                };
                values.add(value);
                current = nextHour;
            }
        }
        
        return TimeseriesResponse.builder()
                .interval(interval)
                .metric(metric)
                .labels(labels)
                .values(values)
                .build();
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
