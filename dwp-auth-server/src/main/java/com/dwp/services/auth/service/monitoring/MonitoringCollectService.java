package com.dwp.services.auth.service.monitoring;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.monitoring.EventCollectRequest;
import com.dwp.services.auth.dto.monitoring.PageViewCollectRequest;
import com.dwp.services.auth.entity.PageViewDailyStat;
import com.dwp.services.auth.entity.PageViewEvent;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.monitoring.EventLog;
import com.dwp.services.auth.repository.PageViewDailyStatRepository;
import com.dwp.services.auth.repository.PageViewEventRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.monitoring.EventLogRepository;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
    private final ResourceRepository resourceRepository;
    private final CodeResolver codeResolver;
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
     * 이벤트 수집 (sys_event_logs에 저장) - 고도화 (BE P1-5 + Hotfix: action normalize)
     * 
     * 정책:
     * - resourceKey로 com_resource 조회
     * - action normalize: 소문자/혼용 입력을 대문자로 정규화
     * - action이 없으면 eventType을 action으로 매핑 (deprecated 지원)
     * - UI_ACTION 코드 검증 (없으면 silent fail)
     * - tracking_enabled=false 이면 silent ignore
     * - com_resource.event_actions 제한 준수 (불일치 시 silent fail)
     * - sys_event_logs에 표준화된 action + resource_kind 저장
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
        if (request.getResourceKey() == null || request.getResourceKey().trim().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "resourceKey는 필수입니다");
        }
        
        // action 또는 eventType 중 하나는 필수 (action 우선)
        String rawAction = request.getAction();
        if ((rawAction == null || rawAction.trim().isEmpty()) && 
            (request.getEventType() == null || request.getEventType().trim().isEmpty())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "action 또는 eventType 중 하나는 필수입니다");
        }
        
        try {
            String resourceKey = truncate(request.getResourceKey(), 255);
            
            // 1. action normalize (action 우선, 없으면 eventType 사용)
            String normalizedAction = normalizeAction(rawAction != null && !rawAction.trim().isEmpty() 
                    ? rawAction 
                    : request.getEventType());
            
            if (normalizedAction == null) {
                log.warn("Action normalization failed: tenantId={}, resourceKey={}, rawAction={}, eventType={}", 
                        tenantId, resourceKey, rawAction, request.getEventType());
                return;  // Silent fail
            }
            
            // 1. com_resource 조회 (tenant_id 기반, 타입 무관)
            List<Resource> resources = resourceRepository.findByTenantIdAndKey(tenantId, resourceKey);
            Optional<Resource> resourceOpt = resources.stream()
                    .filter(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId))  // 테넌트별 리소스 우선
                    .findFirst()
                    .or(() -> resources.stream()
                            .filter(r -> r.getTenantId() == null)  // global resource fallback
                            .findFirst());
            
            if (resourceOpt.isEmpty()) {
                log.warn("Resource not found: tenantId={}, resourceKey={}, skipping event", tenantId, resourceKey);
                return;  // Silent ignore: 리소스가 없으면 이벤트 기록 안 함
            }
            
            Resource resource = resourceOpt.get();
            
            // 2. tracking_enabled=false 이면 silent ignore
            if (resource.getTrackingEnabled() != null && !resource.getTrackingEnabled()) {
                log.debug("Tracking disabled for resource: tenantId={}, resourceKey={}, skipping event", tenantId, resourceKey);
                return;  // Silent ignore
            }
            
            // 2. UI_ACTION 코드 검증 (필수, 없으면 silent fail)
            if (!codeResolver.validate("UI_ACTION", normalizedAction)) {
                log.warn("Action not found in UI_ACTION codes: tenantId={}, resourceKey={}, action={}, skipping event", 
                        tenantId, resourceKey, normalizedAction);
                return;  // Silent fail: UI_ACTION 코드가 없으면 저장하지 않음
            }
            
            // 3. com_resource 기반 이벤트 허용 검증
            String resourceKind = resource.getResourceKind() != null ? resource.getResourceKind() : "PAGE";
            
            // 3.1 tracking_enabled=false 이면 silent ignore
            if (resource.getTrackingEnabled() != null && !resource.getTrackingEnabled()) {
                log.debug("Tracking disabled for resource: tenantId={}, resourceKey={}, skipping event", tenantId, resourceKey);
                return;  // Silent ignore
            }
            
            // 3.2 com_resource.event_actions 제한 준수 검증
            if (!validateAction(resourceKind, normalizedAction, resource.getEventActions())) {
                log.warn("Action not allowed by resource event_actions: tenantId={}, resourceKey={}, resourceKind={}, action={}, eventActions={}, skipping event", 
                        tenantId, resourceKey, resourceKind, normalizedAction, resource.getEventActions());
                return;  // Silent fail: event_actions 제한 위반 시 저장하지 않음
            }
            
            // 4. 이벤트 로그 저장 (표준화된 action + resource_kind 포함)
            String eventType = request.getEventType() != null ? truncate(request.getEventType(), 50) : "USER_ACTION";
            
            EventLog eventLog = EventLog.builder()
                    .tenantId(tenantId)
                    .occurredAt(LocalDateTime.now())
                    .eventType(eventType)
                    .resourceKey(resourceKey)
                    .resourceKind(resourceKind)  // 추가: resource_kind 저장
                    .action(normalizedAction)  // 정규화된 action (대문자)
                    .label(truncate(request.getLabel(), 200))
                    .visitorId(truncate(request.getVisitorId(), 255))
                    .userId(userId)
                    .path(truncate(request.getPath(), 500))
                    .metadata(request.getMetadata())
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .build();
            eventLogRepository.save(eventLog);
            
            log.debug("Event recorded: tenantId={}, resourceKey={}, resourceKind={}, action={}", 
                    tenantId, resourceKey, resourceKind, normalizedAction);
        } catch (Exception e) {
            log.error("Failed to record event: tenantId={}, resourceKey={}", 
                    tenantId, request.getResourceKey(), e);
            // Silent fail
        }
    }
    
    /**
     * action 정규화 (BE Hotfix)
     * 
     * - null/blank면 null 반환
     * - trim 후 대문자 변환
     * - 예) " view " -> "VIEW"
     * - 예) "Click" -> "CLICK"
     * 
     * @param input 원본 action 또는 eventType
     * @return 정규화된 action (대문자), null이면 null 반환
     */
    private String normalizeAction(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        return input.trim().toUpperCase();
    }
    
    /**
     * action 유효성 검증 (resource_kind와 event_actions 기반)
     * 
     * @param resourceKind 리소스 종류
     * @param action 액션 코드 (정규화된 대문자)
     * @param eventActionsJson 허용되는 action 목록 (JSON 배열 문자열)
     * @return 유효성 여부 (true면 허용, false면 거부)
     */
    private boolean validateAction(String resourceKind, String action, String eventActionsJson) {
        if (eventActionsJson == null || eventActionsJson.trim().isEmpty()) {
            // event_actions가 없으면 기본적으로 허용 (제한 없음)
            return true;
        }
        
        try {
            List<String> allowedActions = objectMapper.readValue(eventActionsJson, new TypeReference<List<String>>() {});
            if (allowedActions == null || allowedActions.isEmpty()) {
                return true;  // 빈 배열이면 기본적으로 허용
            }
            
            // 대소문자 구분 없이 비교 (정규화된 action과 비교)
            return allowedActions.stream()
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .anyMatch(allowed -> allowed.equals(action));
        } catch (Exception e) {
            log.warn("Failed to parse event_actions JSON: {}", eventActionsJson, e);
            return true;  // 파싱 실패 시 기본적으로 허용 (안전한 기본값)
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
