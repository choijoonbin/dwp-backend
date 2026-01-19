package com.dwp.services.auth.service.audit;

import com.dwp.services.auth.entity.AuditLog;
import com.dwp.services.auth.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 감사 로그 서비스
 * 
 * 관리 작업의 감사 로그를 기록합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {
    
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * 감사 로그 기록 (비동기)
     * 
     * @param tenantId 테넌트 ID
     * @param actorUserId 행위자 사용자 ID
     * @param action 액션 타입 (USER_CREATE, ROLE_UPDATE 등)
     * @param resourceType 리소스 타입 (USER, ROLE, RESOURCE, CODE 등)
     * @param resourceId 리소스 ID
     * @param before 변경 전 데이터 (JSON)
     * @param after 변경 후 데이터 (JSON)
     * @param request HTTP 요청 (IP, User-Agent 추출용)
     */
    @Async
    @Transactional
    public void recordAuditLog(Long tenantId, Long actorUserId, String action,
                               String resourceType, Long resourceId,
                               Object before, Object after,
                               HttpServletRequest request) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            
            // 변경 전/후 데이터
            if (before != null) {
                metadata.put("before", before);
            }
            if (after != null) {
                metadata.put("after", after);
            }
            
            // IP 주소 및 User-Agent
            if (request != null) {
                String ipAddress = getClientIp(request);
                String userAgent = request.getHeader("User-Agent");
                if (ipAddress != null) {
                    metadata.put("ipAddress", ipAddress);
                }
                if (userAgent != null) {
                    metadata.put("userAgent", userAgent);
                }
            }
            
            String metadataJson = null;
            if (!metadata.isEmpty()) {
                try {
                    metadataJson = objectMapper.writeValueAsString(metadata);
                } catch (Exception e) {
                    log.warn("Failed to serialize audit metadata", e);
                }
            }
            
            AuditLog auditLog = AuditLog.builder()
                    .tenantId(tenantId)
                    .actorUserId(actorUserId)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .metadataJson(metadataJson)
                    .build();
            
            @SuppressWarnings({"null", "unused"})
            AuditLog saved = auditLogRepository.save(auditLog);
            // saved 변수는 null safety 경고를 피하기 위해 유지
        } catch (Exception e) {
            log.error("Failed to record audit log: tenantId={}, action={}, resourceType={}, resourceId={}",
                    tenantId, action, resourceType, resourceId, e);
            // Silent fail: 감사 로그 실패가 메인 로직에 영향을 주지 않도록
        }
    }
    
    /**
     * 간단한 감사 로그 기록 (before/after 없이)
     */
    @Async
    @Transactional
    public void recordAuditLog(Long tenantId, Long actorUserId, String action,
                               String resourceType, Long resourceId,
                               HttpServletRequest request) {
        recordAuditLog(tenantId, actorUserId, action, resourceType, resourceId, null, null, request);
    }
    
    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
