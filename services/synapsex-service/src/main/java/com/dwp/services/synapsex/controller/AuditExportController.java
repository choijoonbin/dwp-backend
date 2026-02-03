package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.audit.AuditExportRequest;
import com.dwp.services.synapsex.dto.audit.AuditExportResponse;
import com.dwp.services.synapsex.service.audit.AuditExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Phase 4 Optional - Audit export (signed URL or job id)
 */
@RestController
@RequestMapping("/synapse/audit")
@RequiredArgsConstructor
public class AuditExportController {

    private final AuditExportService auditExportService;

    @PostMapping("/export")
    public ApiResponse<AuditExportResponse> requestExport(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestBody(required = false) AuditExportRequest request) {
        if (request == null) request = AuditExportRequest.builder().build();
        AuditExportResponse response = auditExportService.requestExport(tenantId, request);
        return ApiResponse.success(response);
    }
}
