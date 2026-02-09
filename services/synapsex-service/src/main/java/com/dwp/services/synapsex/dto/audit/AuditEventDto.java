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
    private String eventCategoryName;
    private String eventType;
    private String eventTypeName;
    private String resourceType;
    private String resourceTypeName;
    private String resourceId;
    private String actorType;
    private String actorTypeName;
    private Long actorUserId;
    private String actorDisplayName;
    private String outcome;
    private String outcomeName;
    private String severity;
    private String severityName;
    private Map<String, Object> evidenceJson;
}
