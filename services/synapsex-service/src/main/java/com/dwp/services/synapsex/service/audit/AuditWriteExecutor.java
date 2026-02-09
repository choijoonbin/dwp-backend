package com.dwp.services.synapsex.service.audit;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.entity.AuditEventLog;
import com.dwp.services.synapsex.repository.AuditEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 감사 로그 실제 저장: REQUIRES_NEW 전용.
 * AuditWriter의 self-injection 순환 참조 회피용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriteExecutor {

    private final AuditEventLogRepository auditEventLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void writeEvent(Long tenantId,
                           String eventCategory,
                           String eventType,
                           String resourceType,
                           String resourceId,
                           String actorType,
                           Long actorUserId,
                           String actorAgentId,
                           String actorDisplayName,
                           String channel,
                           String outcome,
                           String severity,
                           Map<String, Object> beforeJson,
                           Map<String, Object> afterJson,
                           Map<String, Object> diffJson,
                           Map<String, Object> evidenceJson,
                           Map<String, Object> tags,
                           String ipAddress,
                           String userAgent,
                           String gatewayRequestId,
                           String traceId,
                           String spanId) {
        try {
            AuditEventLog e = AuditEventLog.builder()
                    .tenantId(tenantId)
                    .eventCategory(eventCategory != null ? eventCategory : AuditEventConstants.CATEGORY_ADMIN)
                    .eventType(eventType != null ? eventType : AuditEventConstants.TYPE_UPDATE)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .createdAt(Instant.now())
                    .actorType(actorType != null ? actorType : AuditEventConstants.ACTOR_HUMAN)
                    .actorUserId(actorUserId)
                    .actorAgentId(actorAgentId)
                    .actorDisplayName(actorDisplayName)
                    .channel(channel != null ? channel : AuditEventConstants.CHANNEL_API)
                    .outcome(outcome != null ? outcome : AuditEventConstants.OUTCOME_SUCCESS)
                    .severity(severity != null ? severity : AuditEventConstants.SEVERITY_INFO)
                    .beforeJson(beforeJson)
                    .afterJson(afterJson)
                    .diffJson(diffJson)
                    .evidenceJson(evidenceJson)
                    .tags(tags)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .gatewayRequestId(gatewayRequestId)
                    .traceId(traceId)
                    .spanId(spanId)
                    .build();
            auditEventLogRepository.save(e);
        } catch (Exception ex) {
            log.warn("Audit log write failed: {}", ex.getMessage());
        }
    }
}
