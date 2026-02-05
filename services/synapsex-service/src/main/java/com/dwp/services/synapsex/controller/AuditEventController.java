package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.audit.AuditEventDetailDto;
import com.dwp.services.synapsex.dto.audit.AuditEventPageDto;
import com.dwp.services.synapsex.service.audit.AuditEventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Synapse Audit - 감사 이벤트 조회 API.
 * Gateway: /api/synapse/audit/** → /synapse/audit/**
 */
@RestController
@RequestMapping("/synapse/audit/events")
@RequiredArgsConstructor
public class AuditEventController {

    private final AuditEventQueryService queryService;

    @GetMapping
    public ApiResponse<AuditEventPageDto> search(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String eventCategory,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String gatewayRequestId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        var cat = category != null && !category.isBlank() ? category : eventCategory;
        var typ = type != null && !type.isBlank() ? type : eventType;
        com.dwp.services.synapsex.util.DrillDownParamUtil.validateRangeExclusive(range, from, to);
        var tr = com.dwp.services.synapsex.util.DrillDownParamUtil.resolve(range, from, to);
        Instant fromResolved = tr.from();
        Instant toResolved = tr.to();
        AuditEventPageDto page = queryService.search(
                tenantId, fromResolved, toResolved, cat, typ, outcome, severity,
                actorUserId, actorType, resourceType, resourceId, runId, traceId, gatewayRequestId, q, pageable);
        return ApiResponse.success(page);
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditEventDetailDto> getById(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long auditId) {
        AuditEventDetailDto dto = queryService.getById(tenantId, auditId);
        return ApiResponse.success(dto);
    }
}
