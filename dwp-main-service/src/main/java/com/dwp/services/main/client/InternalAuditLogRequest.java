package com.dwp.services.main.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * auth-server POST /internal/audit-logs 요청 DTO
 *
 * dwp-auth-server의 InternalAuditLogRequest와 필드 호환됩니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalAuditLogRequest {

    private Long tenantId;
    private Long actorUserId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private Map<String, Object> metadata;
}
