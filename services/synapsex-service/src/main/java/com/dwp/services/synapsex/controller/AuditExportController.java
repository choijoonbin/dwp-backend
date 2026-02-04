package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.audit.AuditExportRequest;
import com.dwp.services.synapsex.dto.audit.AuditExportResponse;
import com.dwp.services.synapsex.dto.audit.UiEventRequest;
import com.dwp.services.synapsex.service.audit.AuditExportService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 4 Optional - Audit export (signed URL or job id)
 * A2 - UI 이벤트 감사 (POST /api/synapse/audit/ui-events)
 */
@RestController
@RequestMapping("/synapse/audit")
@RequiredArgsConstructor
public class AuditExportController {

    private final AuditExportService auditExportService;
    private final AuditWriter auditWriter;

    /**
     * A2) POST /api/synapse/audit/ui-events
     * UI 클릭/필터 감사 이벤트 기록. event_category=UI, evidence_json에 query/metadata 저장.
     */
    @PostMapping("/ui-events")
    public ApiResponse<Void> recordUiEvent(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody UiEventRequest request) {
        auditWriter.logUiEvent(tenantId, actorUserId, request.getEventType(),
                request.getTargetRoute(), request.getQuery(), request.getMetadata());
        return ApiResponse.success(null);
    }

    @PostMapping("/export")
    public ApiResponse<AuditExportResponse> requestExport(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestBody(required = false) AuditExportRequest request) {
        if (request == null) request = AuditExportRequest.builder().build();
        AuditExportResponse response = auditExportService.requestExport(tenantId, request);
        return ApiResponse.success(response);
    }
}
