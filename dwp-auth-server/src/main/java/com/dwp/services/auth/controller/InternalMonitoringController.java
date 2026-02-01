package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.ApiCallHistoryRequest;
import com.dwp.services.auth.dto.InternalAuditLogRequest;
import com.dwp.services.auth.service.MonitoringService;
import com.dwp.services.auth.service.audit.AuditLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 통신용 컨트롤러
 *
 * Gateway, main-service 등에서 호출하는 내부 API를 제공합니다.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMonitoringController {

    private final MonitoringService monitoringService;
    private final AuditLogService auditLogService;

    /**
     * API 호출 이력 적재
     * POST /internal/api-call-history
     */
    @PostMapping("/api-call-history")
    public ApiResponse<Void> recordApiCallHistory(@RequestBody ApiCallHistoryRequest request) {
        monitoringService.recordApiCallHistory(request);
        return ApiResponse.success();
    }

    /**
     * 감사 로그 기록 (내부 API)
     * POST /internal/audit-logs
     *
     * main-service 등에서 HITL 승인/거절 등 이벤트를 com_audit_logs에 기록할 때 사용합니다.
     * Feign 디코딩 이슈 회피를 위해 204 No Content 반환 (body 없음).
     */
    @PostMapping("/audit-logs")
    public org.springframework.http.ResponseEntity<Void> recordAuditLog(@Valid @RequestBody InternalAuditLogRequest request) {
        auditLogService.recordAuditLog(
                request.getTenantId(),
                request.getActorUserId(),
                request.getAction(),
                request.getResourceType(),
                request.getResourceId(),
                request.getMetadata()
        );
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
