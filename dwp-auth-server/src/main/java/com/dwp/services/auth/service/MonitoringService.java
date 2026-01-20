package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.*;
import com.dwp.services.auth.entity.ApiCallHistory;
import com.dwp.services.auth.entity.PageViewDailyStat;
import com.dwp.services.auth.entity.PageViewEvent;
import com.dwp.services.auth.repository.ApiCallHistoryRepository;
import com.dwp.services.auth.repository.PageViewDailyStatRepository;
import com.dwp.services.auth.repository.PageViewEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MonitoringService {

    private final ApiCallHistoryRepository apiCallHistoryRepository;
    private final PageViewEventRepository pageViewEventRepository;
    private final PageViewDailyStatRepository pageViewDailyStatRepository;
    private final ObjectMapper objectMapper;

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
                    .method(request.getMethod())
                    .path(request.getPath())
                    .queryString(request.getQueryString())
                    .statusCode(request.getStatusCode())
                    .latencyMs(request.getLatencyMs())
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
     * 대시보드 요약 정보 조회
     */
    @Transactional(readOnly = true)
    public MonitoringSummaryResponse getSummary(Long tenantId, LocalDateTime from, LocalDateTime to) {
        long durationDays = ChronoUnit.DAYS.between(from, to);
        if (durationDays <= 0) durationDays = 1;
        LocalDateTime prevFrom = from.minus(durationDays, ChronoUnit.DAYS);
        LocalDateTime prevTo = from;

        long currentPv = pageViewEventRepository.countPvByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentUv = pageViewEventRepository.countUvByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentEvents = pageViewEventRepository.countEventsByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentApiTotal = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, from, to);
        long currentApiErrors = apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, from, to);

        long prevPv = pageViewEventRepository.countPvByTenantIdAndCreatedAtBetween(tenantId, prevFrom, prevTo);
        long prevUv = pageViewEventRepository.countUvByTenantIdAndCreatedAtBetween(tenantId, prevFrom, prevTo);
        long prevEvents = pageViewEventRepository.countEventsByTenantIdAndCreatedAtBetween(tenantId, prevFrom, prevTo);
        long prevApiTotal = apiCallHistoryRepository.countByTenantIdAndCreatedAtBetween(tenantId, prevFrom, prevTo);
        long prevApiErrors = apiCallHistoryRepository.countErrorsByTenantIdAndCreatedAtBetween(tenantId, prevFrom, prevTo);

        double apiErrorRate = currentApiTotal > 0 ? (double) currentApiErrors / currentApiTotal * 100 : 0.0;
        double prevApiErrorRate = prevApiTotal > 0 ? (double) prevApiErrors / prevApiTotal * 100 : 0.0;

        return MonitoringSummaryResponse.builder()
                .pv(currentPv)
                .uv(currentUv)
                .events(currentEvents)
                .apiErrorRate(apiErrorRate)
                .pvDeltaPercent(calculateDelta(currentPv, prevPv))
                .uvDeltaPercent(calculateDelta(currentUv, prevUv))
                .eventDeltaPercent(calculateDelta(currentEvents, prevEvents))
                .apiErrorDeltaPercent(calculateDelta(apiErrorRate, prevApiErrorRate))
                .build();
    }

    private double calculateDelta(double current, double previous) {
        if (previous == 0) return current > 0 ? 100.0 : 0.0;
        return ((current - previous) / previous) * 100;
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
        
        if (from != null && to != null) {
            return pageViewEventRepository.findByTenantIdAndFiltersWithDate(
                    tenantId, from, to, keyword, route, menu, path, userId, pageable);
        } else {
            return pageViewEventRepository.findByTenantIdAndFiltersWithoutDate(
                    tenantId, keyword, route, menu, path, userId, pageable);
        }
    }

    @Transactional(readOnly = true)
    public Page<ApiCallHistory> getApiHistories(Long tenantId, LocalDateTime from, LocalDateTime to,
                                                 String keyword, String apiName, String apiUrl,
                                                 Integer statusCode, Long userId, Pageable pageable) {
        // 빈 문자열을 null로 변환
        keyword = (keyword != null && keyword.trim().isEmpty()) ? null : keyword;
        apiName = (apiName != null && apiName.trim().isEmpty()) ? null : apiName;
        apiUrl = (apiUrl != null && apiUrl.trim().isEmpty()) ? null : apiUrl;
        
        if (from != null && to != null) {
            return apiCallHistoryRepository.findByTenantIdAndFiltersWithDate(
                    tenantId, from, to, keyword, apiName, apiUrl, statusCode, userId, pageable);
        } else {
            return apiCallHistoryRepository.findByTenantIdAndFiltersWithoutDate(
                    tenantId, keyword, apiName, apiUrl, statusCode, userId, pageable);
        }
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
