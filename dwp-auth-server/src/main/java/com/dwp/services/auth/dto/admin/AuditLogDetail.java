package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * P1-4: 감사 로그 상세 DTO
 * AuditLogItem + ipAddress, userAgent, beforeValue, afterValue (metadata_json에서 추출)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogDetail {

    private Long auditLogId;
    private Long tenantId;
    private Long actorUserId;
    private String actorUserName;
    private String action;
    private String resourceType;
    private Long resourceId;
    private Map<String, Object> metadata;
    private Boolean truncated;

    private String ipAddress;
    private String userAgent;
    private Object beforeValue;
    private Object afterValue;

    private LocalDateTime createdAt;
}
