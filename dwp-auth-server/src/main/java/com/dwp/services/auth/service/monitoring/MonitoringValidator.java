package com.dwp.services.auth.service.monitoring;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.monitoring.EventCollectRequest;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 모니터링 검증 컴포넌트
 * 
 * tenantId, resourceKey, action normalize 규칙 검증을 담당합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringValidator {
    
    private final ResourceRepository resourceRepository;
    private final CodeResolver codeResolver;
    private final ObjectMapper objectMapper;
    
    /**
     * tenantId 검증
     * 
     * @param tenantId 테넌트 ID
     * @throws BaseException tenantId가 null인 경우
     */
    public void validateTenantId(Long tenantId) {
        if (tenantId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
        }
    }
    
    /**
     * action 정규화 (대소문자/공백/하이픈 처리)
     * 
     * 정규화 규칙:
     * - 공백 제거
     * - 대문자로 변환
     * - 하이픈은 유지 (예: "CLICK-BUTTON" → "CLICK-BUTTON")
     * 
     * @param action 원본 action
     * @return 정규화된 action (null이면 null 반환)
     */
    public String normalizeAction(String action) {
        if (action == null) {
            return null;
        }
        
        // 공백 제거 후 대문자로 변환
        String normalized = action.trim().toUpperCase();
        
        // 빈 문자열이면 null 반환
        if (normalized.isEmpty()) {
            return null;
        }
        
        return normalized;
    }
    
    /**
     * 리소스 조회 및 tracking_enabled 검증
     * 
     * @param tenantId 테넌트 ID
     * @param resourceKey 리소스 키
     * @return Resource 엔티티 (없으면 null)
     */
    public Resource validateResource(Long tenantId, String resourceKey) {
        if (resourceKey == null || resourceKey.trim().isEmpty()) {
            log.debug("Resource key is null or empty, skipping validation");
            return null;
        }
        
        List<Resource> resources = resourceRepository.findByTenantIdAndKey(tenantId, resourceKey);
        if (resources.isEmpty()) {
            log.debug("Resource not found: tenantId={}, resourceKey={}", tenantId, resourceKey);
            return null;
        }
        
        Resource resource = resources.get(0);
        
        // tracking_enabled=false이면 무시 (silent fail)
        if (!Boolean.TRUE.equals(resource.getTrackingEnabled())) {
            log.debug("Resource tracking is disabled: tenantId={}, resourceKey={}", tenantId, resourceKey);
            return null;
        }
        
        return resource;
    }
    
    /**
     * action이 event_actions에 포함되는지 검증
     * 
     * @param resource 리소스 엔티티
     * @param action 정규화된 action
     * @return 검증 통과 여부
     */
    public boolean validateActionInEventActions(Resource resource, String action) {
        if (resource == null || action == null) {
            return false;
        }
        
        String eventActionsJson = resource.getEventActions();
        if (eventActionsJson == null || eventActionsJson.trim().isEmpty()) {
            log.debug("Event actions is empty: resourceKey={}", resource.getKey());
            return false;
        }
        
        try {
            List<String> eventActions = objectMapper.readValue(eventActionsJson, new TypeReference<List<String>>() {});
            if (eventActions == null || eventActions.isEmpty()) {
                log.debug("Event actions is empty: resourceKey={}", resource.getKey());
                return false;
            }
            
            boolean contains = eventActions.contains(action);
            if (!contains) {
                log.debug("Action not in event_actions: resourceKey={}, action={}, allowedActions={}",
                        resource.getKey(), action, eventActions);
            }
            
            return contains;
        } catch (Exception e) {
            log.warn("Failed to parse event_actions JSON: resourceKey={}, eventActionsJson={}", 
                    resource.getKey(), eventActionsJson, e);
            return false;
        }
    }
    
    /**
     * UI_ACTION 코드 검증
     * 
     * @param action 정규화된 action
     * @param tenantId 테넌트 ID
     * @return 검증 통과 여부
     */
    public boolean validateActionCode(String action, Long tenantId) {
        if (action == null) {
            return false;
        }
        
        // UI_ACTION 코드 그룹에서 검증
        boolean isValid = codeResolver.validate("UI_ACTION", action);
        if (!isValid) {
            log.debug("Invalid UI_ACTION code: action={}, tenantId={}", action, tenantId);
        }
        
        return isValid;
    }
    
    /**
     * 이벤트 수집 요청 검증 (통합)
     * 
     * @param tenantId 테넌트 ID
     * @param request 이벤트 수집 요청
     * @return 검증 통과 시 Resource 엔티티, 실패 시 null (silent fail)
     */
    public Resource validateEventCollectRequest(Long tenantId, EventCollectRequest request) {
        validateTenantId(tenantId);
        
        // action 정규화
        String normalizedAction = normalizeAction(request.getAction() != null ? request.getAction() : request.getEventType());
        if (normalizedAction == null) {
            log.debug("Action is null or empty after normalization");
            return null;
        }
        
        // resourceKey 검증
        String resourceKey = request.getResourceKey();
        Resource resource = validateResource(tenantId, resourceKey);
        if (resource == null) {
            return null;  // silent fail
        }
        
        // UI_ACTION 코드 검증
        if (!validateActionCode(normalizedAction, tenantId)) {
            log.debug("Invalid UI_ACTION code: action={}", normalizedAction);
            return null;  // silent fail
        }
        
        // event_actions 포함 여부 검증
        if (!validateActionInEventActions(resource, normalizedAction)) {
            log.debug("Action not in event_actions: resourceKey={}, action={}", resourceKey, normalizedAction);
            return null;  // silent fail
        }
        
        return resource;
    }
}
