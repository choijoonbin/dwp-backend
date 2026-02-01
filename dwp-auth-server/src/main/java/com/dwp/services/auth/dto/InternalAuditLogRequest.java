package com.dwp.services.auth.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 내부 감사 로그 기록 요청 DTO
 *
 * main-service 등 다른 서비스에서 POST /internal/audit-logs 로 호출할 때 사용합니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalAuditLogRequest {

    @NotNull
    private Long tenantId;

    private Long actorUserId;

    @NotNull
    private String action;

    private String resourceType;

    private Long resourceId;

    private Map<String, Object> metadata;
}
