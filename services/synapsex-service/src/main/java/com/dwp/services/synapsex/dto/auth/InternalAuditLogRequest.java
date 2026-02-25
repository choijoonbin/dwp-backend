package com.dwp.services.synapsex.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
