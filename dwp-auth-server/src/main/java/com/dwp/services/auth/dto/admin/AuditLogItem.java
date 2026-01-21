package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * PR-08A: 감사 로그 항목 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogItem {
    
    private Long auditLogId;
    private Long tenantId;
    private Long actorUserId;
    private String actorUserName; // 조인하여 가져올 수 있으면
    private String action; // USER_CREATE, ROLE_UPDATE 등
    private String resourceType; // USER, ROLE, RESOURCE, CODE 등
    private Long resourceId;
    private Map<String, Object> metadata; // JSON 파싱된 메타데이터
    private Boolean truncated; // PR-08B: before/after가 잘렸는지 여부
    private LocalDateTime createdAt;
}
