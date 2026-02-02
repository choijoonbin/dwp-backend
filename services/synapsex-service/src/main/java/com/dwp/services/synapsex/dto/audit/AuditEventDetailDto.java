package com.dwp.services.synapsex.dto.audit;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class AuditEventDetailDto {
    private Long auditId;
    private Long tenantId;
    private Instant createdAt;
    private String eventCategory;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private String actorType;
    private Long actorUserId;
    private String actorAgentId;
    private String actorDisplayName;
    private String channel;
    private String outcome;
    private String severity;
    private Map<String, Object> beforeJson;
    private Map<String, Object> afterJson;
    private Map<String, Object> diffJson;
    private Map<String, Object> evidenceJson;
    private Map<String, Object> tags;
    private String ipAddress;
    private String userAgent;
    private String gatewayRequestId;
    private String traceId;
    private String spanId;
}
