package com.dwp.services.synapsex.dto.audit;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class AuditEventDto {
    private Long auditId;
    private Instant createdAt;
    private String eventCategory;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private String actorType;
    private Long actorUserId;
    private String actorDisplayName;
    private String outcome;
    private String severity;
    private Map<String, Object> evidenceJson;
}
