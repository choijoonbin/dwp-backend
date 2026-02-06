package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.audit.AuditRequestContext;
import com.dwp.services.synapsex.dto.audit.AuditEventDetailDto;
import com.dwp.services.synapsex.dto.audit.AuditEventPageDto;
import com.dwp.services.synapsex.service.audit.AuditEventQueryService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Synapse Audit - 감사 이벤트 조회 API.
 * Gateway: /api/synapse/audit/** → /synapse/audit/**
 */
@RestController
@RequestMapping("/synapse/audit/events")
@RequiredArgsConstructor
public class AuditEventController {

    private final AuditEventQueryService queryService;
    private final AuditWriter auditWriter;

    @GetMapping
    public ApiResponse<AuditEventPageDto> search(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long currentUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String eventCategory,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false, name = "actorUserId") Long filterActorUserId,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String gatewayRequestId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            HttpServletRequest httpRequest) {
        var cat = category != null && !category.isBlank() ? category : eventCategory;
        var typ = type != null && !type.isBlank() ? type : eventType;
        com.dwp.services.synapsex.util.DrillDownParamUtil.validateRangeExclusive(range, from, to);
        var tr = com.dwp.services.synapsex.util.DrillDownParamUtil.resolve(range, from, to);
        Instant fromResolved = tr.from();
        Instant toResolved = tr.to();
        AuditEventPageDto page = queryService.search(
                tenantId, fromResolved, toResolved, cat, typ, outcome, severity,
                filterActorUserId, actorType, resourceType, resourceId, runId, traceId, gatewayRequestId, q, pageable);
        Map<String, Object> filters = new HashMap<>();
        if (cat != null && !cat.isBlank()) filters.put("category", cat);
        if (typ != null && !typ.isBlank()) filters.put("type", typ);
        if (outcome != null && !outcome.isBlank()) filters.put("outcome", outcome);
        Map<String, Object> tags = AuditRequestContext.listTags(
                pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().stream().findFirst().map(s -> s.getProperty()).orElse("createdAt"),
                pageable.getSort().stream().findFirst().map(s -> s.getDirection().name()).orElse("DESC"),
                filters);
        String actorTypeVal = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_AUDIT, AuditEventConstants.TYPE_AUDIT_VIEW_LIST,
                AuditEventConstants.RESOURCE_AUDIT_EVENT, null, actorTypeVal, currentUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(page);
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditEventDetailDto> getById(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable Long auditId,
            HttpServletRequest httpRequest) {
        AuditEventDetailDto dto = queryService.getById(tenantId, auditId);
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        Map<String, Object> tags = Map.of("auditId", auditId);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_AUDIT, AuditEventConstants.TYPE_AUDIT_VIEW_DETAIL,
                AuditEventConstants.RESOURCE_AUDIT_EVENT, String.valueOf(auditId), actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);
        return ApiResponse.success(dto);
    }
}
