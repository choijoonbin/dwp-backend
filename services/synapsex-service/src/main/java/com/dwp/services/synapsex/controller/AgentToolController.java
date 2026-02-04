package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.dto.agent_tools.*;
import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.document.DocumentDetailDto;
import com.dwp.services.synapsex.dto.document.DocumentListRowDto;
import com.dwp.services.synapsex.dto.entity.Entity360Dto;
import com.dwp.services.synapsex.dto.lineage.LineageResponseDto;
import com.dwp.services.synapsex.dto.openitem.OpenItemListRowDto;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.service.agent_tools.AgentToolCommandService;
import com.dwp.services.synapsex.service.agent_tools.AgentToolQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Agent Tool API — Aura가 호출할 표준 Tool API
 * Base: /api/synapse/agent-tools/**
 * 공통: X-Tenant-ID 필수, X-User-ID 선택, ApiResponse&lt;T&gt;, 감사로그 기록
 */
@RestController
@RequestMapping("/synapse/agent-tools")
@RequiredArgsConstructor
public class AgentToolController {

    private final AgentToolQueryService agentToolQueryService;
    private final AgentToolCommandService agentToolCommandService;

    // --- Read Tools ---

    /**
     * GET /agent-tools/cases/{caseId}
     */
    @GetMapping("/cases/{caseId}")
    public ApiResponse<CaseDetailDto> getCase(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId) {
        CaseDetailDto dto = agentToolQueryService.getCase(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * GET /agent-tools/documents
     * filter: bukrs, gjahr, vendor/customer, date range, amount range, anomaly flags
     */
    @GetMapping("/documents")
    public ApiResponse<PageResponse<DocumentListRowDto>> getDocuments(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) String gjahr,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) Boolean anomalyFlags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<DocumentListRowDto> result = agentToolQueryService.getDocuments(
                tenantId, bukrs, gjahr, vendorId, customerId,
                fromDate, toDate, amountMin, amountMax, anomalyFlags,
                page, size, sort);
        return ApiResponse.success(result);
    }

    /**
     * GET /agent-tools/documents/{bukrs}/{belnr}/{gjahr}
     * header + lines + reversal chain
     */
    @GetMapping("/documents/{bukrs}/{belnr}/{gjahr}")
    public ApiResponse<DocumentDetailDto> getDocumentDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable String bukrs,
            @PathVariable String belnr,
            @PathVariable String gjahr) {
        DocumentDetailDto dto = agentToolQueryService.getDocumentDetail(tenantId, bukrs, belnr, gjahr)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "전표를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * GET /agent-tools/entities/{entityId}
     * profile + change log summary + linked objects
     */
    @GetMapping("/entities/{entityId}")
    public ApiResponse<Entity360Dto> getEntity(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long entityId) {
        Entity360Dto dto = agentToolQueryService.getEntity(tenantId, entityId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "거래처를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * GET /agent-tools/open-items
     * AR/AP, overdue bucket
     */
    @GetMapping("/open-items")
    public ApiResponse<PageResponse<OpenItemListRowDto>> getOpenItems(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer overdueBucket,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<OpenItemListRowDto> result = agentToolQueryService.getOpenItems(
                tenantId, type, overdueBucket, page, size, sort);
        return ApiResponse.success(result);
    }

    /**
     * GET /agent-tools/lineage?caseId=...
     * raw→ingestion→scoring→case→action
     */
    @GetMapping("/lineage")
    public ApiResponse<LineageResponseDto> getLineage(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam Long caseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        LineageResponseDto result = agentToolQueryService.getLineage(tenantId, caseId, asOf);
        return ApiResponse.success(result);
    }

    // --- Write Tools ---

    /**
     * POST /agent-tools/actions/simulate
     * before/after preview + validation errors + predicted SAP impact
     */
    @PostMapping("/actions/simulate")
    public ApiResponse<ActionSimulateResponse> simulate(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @Valid @RequestBody ActionSimulateRequest request) {
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        ActionSimulateResponse result = agentToolCommandService.simulate(
                tenantId, request.getCaseId(), request.getActionType(), request.getPayload(),
                actorType, actorUserId, actorAgentId);
        return ApiResponse.success(result);
    }

    /**
     * POST /agent-tools/actions/propose
     * 승인 필요 여부 판정, 승인 필요 시 HITL request 생성(= pending)
     */
    @PostMapping("/actions/propose")
    public ApiResponse<ActionProposeResponse> propose(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @Valid @RequestBody ActionProposeRequest request) {
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        ActionProposeResponse result = agentToolCommandService.propose(
                tenantId, request.getCaseId(), request.getActionType(), request.getPayload(),
                actorType, actorUserId, actorAgentId);
        return ApiResponse.success(result);
    }

    /**
     * POST /agent-tools/actions/{actionId}/execute
     * 승인 완료된 action만 실행
     */
    @PostMapping("/actions/{actionId}/execute")
    public ApiResponse<ActionDetailDto> execute(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable Long actionId) {
        String actorType = actorAgentId != null ? AuditEventConstants.ACTOR_AGENT : AuditEventConstants.ACTOR_HUMAN;
        AgentAction action = agentToolCommandService.execute(tenantId, actionId, actorType, actorUserId);
        ActionDetailDto dto = ActionDetailDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus() != null ? action.getStatus().name() : null)
                .payload(action.getPayloadJson())
                .simulationBefore(action.getSimulationBefore())
                .simulationAfter(action.getSimulationAfter())
                .diffJson(action.getDiffJson())
                .createdAt(action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt())
                .build();
        return ApiResponse.success(dto);
    }
}
