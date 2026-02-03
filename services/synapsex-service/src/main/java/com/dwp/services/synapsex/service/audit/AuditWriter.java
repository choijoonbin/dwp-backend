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
import java.util.HashMap;
import java.util.Map;

/**
 * Synapse 감사 로그 기록. SoT: dwp_aura.audit_event_log
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriter {

    private final AuditEventLogRepository auditEventLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void log(Long tenantId,
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

    /** Admin 정책 변경 기록 (간편) */
    public void logAdminChange(Long tenantId, Long actorUserId, String eventType,
                               String resourceType, String resourceId,
                               Map<String, Object> beforeJson, Map<String, Object> afterJson,
                               String ipAddress, String userAgent, String gatewayRequestId) {
        log(tenantId,
                AuditEventConstants.CATEGORY_ADMIN,
                eventType,
                resourceType,
                resourceId,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                beforeJson,
                afterJson,
                null,
                null,
                null,
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** Admin BULK 변경 기록 (diff_json에 변경된 키/상세) */
    public void logAdminBulkChange(Long tenantId, Long actorUserId, String resourceType,
                                  Map<String, Object> diffJson,
                                  String ipAddress, String userAgent, String gatewayRequestId) {
        log(tenantId,
                AuditEventConstants.CATEGORY_ADMIN,
                AuditEventConstants.TYPE_BULK_UPDATE,
                resourceType,
                null,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                null,
                null,
                diffJson,
                null,
                null,
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    // --- Tenant Scope 전용 (tags + diff_json 구조화) ---

    /** diff_json 구조화 헬퍼: {"field": {"before":x,"after":y}} */
    public static Map<String, Object> buildDiffJson(String field, Object before, Object after) {
        Map<String, Object> diff = new HashMap<>();
        diff.put(field, Map.of("before", before != null ? before : "null", "after", after != null ? after : "null"));
        return diff;
    }

    /** Tenant Scope tags: module, tab, area, bulkCount */
    public static Map<String, Object> tenantScopeTags(String area, Integer bulkCount) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "TENANT_SCOPE");
        tags.put("tab", "Tenant Scope");
        tags.put("area", area);
        if (bulkCount != null) tags.put("bulkCount", bulkCount);
        return tags;
    }

    /** Tenant Scope 단건 변경 기록 (tags + diff_json) */
    public void logTenantScopeChange(Long tenantId, Long actorUserId, String eventType,
                                     String resourceType, String resourceId,
                                     Map<String, Object> diffJson, String area,
                                     String ipAddress, String userAgent, String gatewayRequestId) {
        log(tenantId,
                AuditEventConstants.CATEGORY_ADMIN,
                eventType,
                resourceType,
                resourceId,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                null,
                null,
                diffJson,
                null,
                tenantScopeTags(area, null),
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** Tenant Scope BULK 변경 기록 */
    public void logTenantScopeBulkChange(Long tenantId, Long actorUserId, String resourceType,
                                          Map<String, Object> diffJson, String area, int bulkCount,
                                          String ipAddress, String userAgent, String gatewayRequestId) {
        log(tenantId,
                AuditEventConstants.CATEGORY_ADMIN,
                AuditEventConstants.TYPE_BULK_UPDATE,
                resourceType,
                null,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                null,
                null,
                diffJson,
                null,
                tenantScopeTags(area, bulkCount),
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** PII Policy BULK 변경 기록 (before/after/diff filled) */
    public void logPiiPolicyBulkChange(Long tenantId, Long actorUserId, String profileId,
                                       Map<String, Object> beforeJson, Map<String, Object> afterJson,
                                       Map<String, Object> diffJson) {
        log(tenantId,
                AuditEventConstants.CATEGORY_ADMIN,
                AuditEventConstants.TYPE_BULK_UPDATE,
                AuditEventConstants.RESOURCE_PII_POLICY,
                profileId,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                beforeJson,
                afterJson,
                diffJson,
                null,
                Map.of("module", "PII_POLICY", "tab", "PII & Encryption"),
                null,
                null,
                null,
                null,
                null);
    }

    /** Case 상태 변경 기록 (event_category=CASE, event_type=STATUS_CHANGE) */
    public void logCaseStatusChange(Long tenantId, Long caseId, String oldStatus, String newStatus,
                                    Long actorUserId, String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> diff = new HashMap<>();
        diff.put("status", Map.of("before", oldStatus != null ? oldStatus : "null", "after", newStatus != null ? newStatus : "null"));
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "CASE");
        tags.put("caseId", caseId);
        log(tenantId,
                AuditEventConstants.CATEGORY_CASE,
                AuditEventConstants.TYPE_STATUS_CHANGE,
                "AGENT_CASE",
                String.valueOf(caseId),
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                Map.of("status", oldStatus != null ? oldStatus : ""),
                Map.of("status", newStatus != null ? newStatus : ""),
                diff,
                null,
                tags,
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** Action 승인/실행 기록 (event_category=ACTION) */
    public void logActionEvent(Long tenantId, String eventType, Long actionId, Long caseId,
                               Long actorUserId, String outcome, Map<String, Object> beforeJson, Map<String, Object> afterJson,
                               String ipAddress, String userAgent, String gatewayRequestId) {
        logActionEventInternal(tenantId, eventType, actionId, caseId, actorUserId, outcome,
                beforeJson, afterJson, ipAddress, userAgent, gatewayRequestId, null);
    }

    /**
     * Action 실패 기록 (event_category=ACTION, outcome=FAILED) — auditId 반환.
     * FE "Audit 상세 보기" 링크용.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Long logActionEventFailure(Long tenantId, String eventType, Long actionId, Long caseId,
                                     Long actorUserId, String errorMessage,
                                     String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> afterJson = new HashMap<>();
        afterJson.put("error", errorMessage != null ? errorMessage : "Unknown error");
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "ACTION");
        if (actionId != null) tags.put("actionId", actionId);
        if (caseId != null) tags.put("caseId", caseId);
        try {
            AuditEventLog e = AuditEventLog.builder()
                    .tenantId(tenantId)
                    .eventCategory(AuditEventConstants.CATEGORY_ACTION)
                    .eventType(eventType)
                    .resourceType("AGENT_ACTION")
                    .resourceId(actionId != null ? String.valueOf(actionId) : null)
                    .createdAt(Instant.now())
                    .actorType(AuditEventConstants.ACTOR_HUMAN)
                    .actorUserId(actorUserId)
                    .channel(AuditEventConstants.CHANNEL_API)
                    .outcome(AuditEventConstants.OUTCOME_FAILED)
                    .severity(AuditEventConstants.SEVERITY_WARN)
                    .afterJson(afterJson)
                    .tags(tags)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .gatewayRequestId(gatewayRequestId)
                    .build();
            e = auditEventLogRepository.save(e);
            return e.getAuditId();
        } catch (Exception ex) {
            log.warn("Audit failure log write failed: {}", ex.getMessage());
            return null;
        }
    }

    private void logActionEventInternal(Long tenantId, String eventType, Long actionId, Long caseId,
                                        Long actorUserId, String outcome, Map<String, Object> beforeJson, Map<String, Object> afterJson,
                                        String ipAddress, String userAgent, String gatewayRequestId, Map<String, Object> tags) {
        Map<String, Object> t = tags != null ? new HashMap<>(tags) : new HashMap<>();
        t.put("module", "ACTION");
        t.put("actionId", actionId);
        t.put("caseId", caseId);
        log(tenantId,
                AuditEventConstants.CATEGORY_ACTION,
                eventType,
                "AGENT_ACTION",
                String.valueOf(actionId),
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                outcome != null ? outcome : AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                beforeJson,
                afterJson,
                null,
                null,
                t,
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** Scope 위반 DENIED 기록 (403 OUT_OF_SCOPE) */
    public void logScopeDenied(Long tenantId, Long actorUserId, String resourceType, String resourceId,
                               String bukrs, String currency, String reason,
                               String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "SCOPE_ENFORCEMENT");
        tags.put("bukrs", bukrs);
        tags.put("currency", currency);
        tags.put("reason", reason);
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("out_of_scope", true);
        log(tenantId,
                AuditEventConstants.CATEGORY_POLICY,
                "ACCESS_DENIED",
                resourceType,
                resourceId,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_DENIED,
                AuditEventConstants.SEVERITY_WARN,
                null,
                null,
                null,
                evidence,
                tags,
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** Integration Outbox Enqueue / Result Update 기록 (event_category=INTEGRATION) */
    public void logIntegrationEvent(Long tenantId, String eventType, String resourceType, String resourceId,
                                    Long actorUserId, String outcome,
                                    Map<String, Object> beforeJson, Map<String, Object> afterJson,
                                    String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> tags = new HashMap<>();
        tags.put("module", "INTEGRATION");
        if (resourceId != null) tags.put("resourceId", resourceId);
        log(tenantId,
                AuditEventConstants.CATEGORY_INTEGRATION,
                eventType,
                resourceType,
                resourceId,
                AuditEventConstants.ACTOR_SYSTEM,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_INTEGRATION,
                outcome != null ? outcome : AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                beforeJson,
                afterJson,
                null,
                null,
                tags,
                ipAddress,
                userAgent,
                gatewayRequestId,
                null,
                null);
    }

    /** Data Protection UPDATE 기록 */
    public void logDataProtectionUpdate(Long tenantId, Long actorUserId, String profileId,
                                        Map<String, Object> beforeJson, Map<String, Object> afterJson,
                                        Map<String, Object> diffJson) {
        log(tenantId,
                AuditEventConstants.CATEGORY_ADMIN,
                AuditEventConstants.TYPE_UPDATE,
                AuditEventConstants.RESOURCE_DATA_PROTECTION,
                profileId,
                AuditEventConstants.ACTOR_HUMAN,
                actorUserId,
                null,
                null,
                AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS,
                AuditEventConstants.SEVERITY_INFO,
                beforeJson,
                afterJson,
                diffJson,
                null,
                Map.of("module", "DATA_PROTECTION", "tab", "PII & Encryption"),
                null,
                null,
                null,
                null,
                null);
    }
}
