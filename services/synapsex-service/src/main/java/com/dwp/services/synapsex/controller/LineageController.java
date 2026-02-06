package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.audit.AuditRequestContext;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.lineage.LineageQueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 1 Lineage / Evidence Viewer API
 * GET /api/synapse/lineage
 */
@RestController
@RequestMapping("/synapse/lineage")
@RequiredArgsConstructor
public class LineageController {

    private final LineageQueryService lineageQueryService;
    private final AuditWriter auditWriter;

    /**
     * D1) GET /api/synapse/lineage
     * Query: caseId OR docKey OR rawEventId OR partyId (최소 1개 필수)
     */
    @GetMapping
    public ApiResponse<LineageResponseDto> getLineage(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @RequestParam(required = false) Long caseId,
            @RequestParam(required = false) String docKey,
            @RequestParam(required = false) Long rawEventId,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            HttpServletRequest httpRequest) {

        var query = LineageQueryService.LineageQuery.builder()
                .caseId(caseId)
                .docKey(docKey)
                .rawEventId(rawEventId)
                .partyId(partyId)
                .asOf(asOf)
                .build();

        LineageResponseDto result = lineageQueryService.findLineage(tenantId, query);

        Map<String, Object> tags = caseId != null ? Map.of("caseId", caseId) : (docKey != null ? Map.of("docKey", docKey) : Map.of());
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_LINEAGE_VIEW,
                "LINEAGE", caseId != null ? String.valueOf(caseId) : docKey, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);

        return ApiResponse.success(result);
    }

    /**
     * D2) GET /api/synapse/lineage/time-travel
     * Query: partyId, asOf(datetime) — Phase1 MVP: change log 기반 추정
     */
    @GetMapping("/time-travel")
    public ApiResponse<LineageResponseDto> getTimeTravel(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            HttpServletRequest httpRequest) {

        var query = LineageQueryService.LineageQuery.builder()
                .partyId(partyId)
                .asOf(asOf)
                .build();

        LineageResponseDto result = lineageQueryService.findLineage(tenantId, query);

        Map<String, Object> tags = partyId != null ? Map.of("partyId", partyId, "timeTravel", true) : Map.of("timeTravel", true);
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_LINEAGE_VIEW,
                "LINEAGE", null, actorType, actorUserId, actorAgentId, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, tags, AuditRequestContext.getIpAddress(httpRequest), AuditRequestContext.getUserAgent(httpRequest),
                AuditRequestContext.getGatewayRequestId(httpRequest), AuditRequestContext.getTraceId(httpRequest), null);

        return ApiResponse.success(result);
    }
}
