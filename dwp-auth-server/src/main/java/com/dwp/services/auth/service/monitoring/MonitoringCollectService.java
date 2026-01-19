package com.dwp.services.auth.service.monitoring;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.monitoring.EventCollectRequest;
import com.dwp.services.auth.dto.monitoring.PageViewCollectRequest;
import com.dwp.services.auth.entity.PageViewDailyStat;
import com.dwp.services.auth.entity.PageViewEvent;
import com.dwp.services.auth.entity.monitoring.EventLog;
import com.dwp.services.auth.repository.PageViewDailyStatRepository;
import com.dwp.services.auth.repository.PageViewEventRepository;
import com.dwp.services.auth.repository.monitoring.EventLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 모니터링 수집 서비스
 * 
 * 페이지뷰 및 이벤트 수집을 담당합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MonitoringCollectService {
    
    private final PageViewEventRepository pageViewEventRepository;
    private final PageViewDailyStatRepository pageViewDailyStatRepository;
    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;
    
    // 중복 방지: (tenant_id, visitor_id, path) 기준 1초 이내 중복 체크
    // TODO: Redis 기반 분산 락으로 개선 가능
    
    /**
     * 페이지뷰 수집
     * 
     * @param tenantId 테넌트 ID (필수)
     * @param userId 사용자 ID (선택)
     * @param ipAddress IP 주소
     * @param userAgent User-Agent
     * @param request 페이지뷰 요청
     * @throws BaseException tenantId가 null이거나 path가 비어있는 경우
     */
    @Transactional
    public void recordPageView(Long tenantId, Long userId, String ipAddress, String userAgent, PageViewCollectRequest request) {
        // Validation
        if (tenantId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
        if (request.getPath() == null || request.getPath().trim().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "path는 필수입니다");
        }
        
        try {
            // 문자열 길이 제한 (500자)
            String path = truncate(request.getPath(), 500);
            String pageKey = request.getMenuKey() != null ? truncate(request.getMenuKey(), 255) : path;
            String visitorId = request.getVisitorId() != null ? truncate(request.getVisitorId(), 255) : null;
            
            // 1. Raw 이벤트 저장
            PageViewEvent event = PageViewEvent.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .sessionId(visitorId)
                    .pageKey(pageKey)
                    .referrer(truncate(request.getReferrer(), 500))
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .eventType("PAGE_VIEW")
                    .eventName(truncate(request.getTitle(), 100))
                    .metadataJson(toJson(request.getMetadata()))
                    .build();
            pageViewEventRepository.save(event);
            
            // 2. 일별 집계 업데이트 (Upsert)
            updateDailyStats(tenantId, pageKey, visitorId);
        } catch (Exception e) {
            log.error("Failed to record page view: tenantId={}, path={}", tenantId, request.getPath(), e);
            // Silent fail: 수집 실패가 FE에 영향을 주지 않도록 예외를 다시 던지지 않음
        }
    }
    
    /**
     * 이벤트 수집 (sys_event_logs에 저장)
     * 
     * @param tenantId 테넌트 ID (필수)
     * @param userId 사용자 ID (선택)
     * @param ipAddress IP 주소
     * @param userAgent User-Agent
     * @param request 이벤트 요청
     * @throws BaseException tenantId가 null이거나 필수 필드가 누락된 경우
     */
    @Transactional
    public void recordEvent(Long tenantId, Long userId, String ipAddress, String userAgent, EventCollectRequest request) {
        // Validation
        if (tenantId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
        if (request.getEventType() == null || request.getEventType().trim().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "eventType은 필수입니다");
        }
        if (request.getResourceKey() == null || request.getResourceKey().trim().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "resourceKey는 필수입니다");
        }
        if (request.getAction() == null || request.getAction().trim().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "action은 필수입니다");
        }
        
        try {
            // 문자열 길이 제한
            String eventType = truncate(request.getEventType(), 50);
            String resourceKey = truncate(request.getResourceKey(), 255);
            String action = truncate(request.getAction(), 100);
            
            EventLog eventLog = EventLog.builder()
                    .tenantId(tenantId)
                    .occurredAt(LocalDateTime.now())
                    .eventType(eventType)
                    .resourceKey(resourceKey)
                    .action(action)
                    .label(truncate(request.getLabel(), 200))
                    .visitorId(truncate(request.getVisitorId(), 255))
                    .userId(userId)
                    .path(truncate(request.getPath(), 500))
                    .metadata(request.getMetadata())
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .build();
            eventLogRepository.save(eventLog);
        } catch (Exception e) {
            log.error("Failed to record event: tenantId={}, eventType={}, resourceKey={}", 
                    tenantId, request.getEventType(), request.getResourceKey(), e);
            // Silent fail
        }
    }
    
    private void updateDailyStats(Long tenantId, String pageKey, String visitorId) {
        LocalDate today = LocalDate.now();
        Optional<PageViewDailyStat> statOpt = pageViewDailyStatRepository.findByTenantIdAndStatDateAndPageKey(tenantId, today, pageKey);
        
        if (statOpt.isPresent()) {
            PageViewDailyStat stat = statOpt.get();
            stat.setPvCount(stat.getPvCount() + 1);
            // UV는 별도 배치로 집계하는 것을 권장 (현재는 단순 증가)
            pageViewDailyStatRepository.save(stat);
        } else {
            PageViewDailyStat stat = PageViewDailyStat.builder()
                    .tenantId(tenantId)
                    .statDate(today)
                    .pageKey(pageKey)
                    .pvCount(1L)
                    .uvCount(visitorId != null ? 1L : 0L)
                    .uniqueSessionCount(visitorId != null ? 1L : 0L)
                    .build();
            pageViewDailyStatRepository.save(stat);
        }
    }
    
    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
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
