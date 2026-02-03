package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.case_.*;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.service.case_.CaseQueryService.CaseListQuery;
import com.dwp.services.synapsex.service.case_.CaseCommandService;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.scope.ScopeEnforcementService;
import com.dwp.services.synapsex.util.DrillDownParamUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Phase 2 Cases API
 */
@RestController
@RequestMapping("/synapse/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseQueryService caseQueryService;
    private final CaseCommandService caseCommandService;
    private final AuditWriter auditWriter;
    private final ScopeEnforcementService scopeEnforcementService;

    /**
     * A1) GET /api/synapse/cases
     */
    @GetMapping
    public ApiResponse<PageResponse<CaseListRowDto>> getCases(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String caseType,
            @RequestParam(required = false) String driverType,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Long assigneeUserId,
            @RequestParam(required = false) String slaRisk,
            @RequestParam(required = false) String companyCode,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String waers,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant detectedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant detectedTo,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) String belnr,
            @RequestParam(required = false) String gjahr,
            @RequestParam(required = false) String buzei,
            @RequestParam(required = false) Long partyId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String caseKey,
            @RequestParam(required = false) String documentKey,
            @RequestParam(required = false) Boolean hasPendingAction,
            @RequestParam(required = false) String savedViewKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String order) {

        var timeRange = DrillDownParamUtil.resolve(range, from != null ? from : dateFrom, to != null ? to : dateTo);
        Instant resolvedFrom = timeRange.from();
        Instant resolvedTo = timeRange.to();

        List<String> requestedCompany = DrillDownParamUtil.parseMulti(company);
        if (requestedCompany.isEmpty() && companyCode != null && !companyCode.isBlank()) {
            requestedCompany = List.of(companyCode.trim());
        }
        if (requestedCompany.isEmpty() && bukrs != null && !bukrs.isBlank()) {
            requestedCompany = List.of(bukrs.trim());
        }
        List<String> resolvedCompany = scopeEnforcementService.resolveCompanyFilter(tenantId, null, requestedCompany);

        Long resolvedAssignee = assigneeUserId;
        if (resolvedAssignee == null && assignee != null && !assignee.isBlank()) {
            try {
                resolvedAssignee = Long.parseLong(assignee.trim());
            } catch (NumberFormatException ignored) {}
        }

        var query = CaseListQuery.builder()
                .status(status)
                .severity(severity)
                .caseType(caseType != null ? caseType : driverType)
                .assigneeUserId(resolvedAssignee)
                .slaRisk(slaRisk)
                .companyCode(companyCode != null ? companyCode : bukrs)
                .company(resolvedCompany.isEmpty() ? null : resolvedCompany)
                .waers(waers)
                .dateFrom(resolvedFrom)
                .dateTo(resolvedTo)
                .detectedFrom(detectedFrom != null ? detectedFrom : resolvedFrom)
                .detectedTo(detectedTo != null ? detectedTo : resolvedTo)
                .bukrs(bukrs)
                .belnr(belnr)
                .gjahr(gjahr)
                .buzei(buzei)
                .partyId(partyId)
                .q(q)
                .ids(ids != null ? DrillDownParamUtil.parseIds(ids) : List.of())
                .range(range)
                .caseKey(caseKey)
                .documentKey(documentKey)
                .hasPendingAction(hasPendingAction)
                .savedViewKey(savedViewKey)
                .page(page < 1 ? 0 : page - 1)
                .size(Math.min(200, Math.max(1, size)))
                .sort(sort)
                .order(order)
                .build();

        PageResponse<CaseListRowDto> result = caseQueryService.findCases(tenantId, query);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_VIEW_LIST,
                "CASE_LIST", null, AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, null, null, null, null, null, null);
        return ApiResponse.success(result);
    }

    /**
     * A2) GET /api/synapse/cases/{caseId}
     */
    @GetMapping("/{caseId}")
    public ApiResponse<CaseDetailDto> getCaseDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long caseId) {

        CaseDetailDto dto = caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_VIEW_DETAIL,
                "AGENT_CASE", String.valueOf(caseId), AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, null, null, null, null, null, null);
        return ApiResponse.success(dto);
    }

    /**
     * GET /api/synapse/cases/{caseId}/timeline
     */
    @GetMapping("/{caseId}/timeline")
    public ApiResponse<List<CaseTimelineDto>> getTimeline(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<CaseTimelineDto> timeline = caseQueryService.findTimeline(tenantId, caseId, page, size);
        return ApiResponse.success(timeline);
    }

    /**
     * A3) POST /api/synapse/cases/{caseId}/status
     */
    @PostMapping("/{caseId}/status")
    public ApiResponse<CaseDetailDto> updateCaseStatus(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long caseId,
            @Valid @RequestBody CaseStatusUpdateRequest request) {

        caseCommandService.updateCaseStatus(tenantId, caseId, request.getStatus(),
                actorUserId, null, null, null);
        CaseDetailDto dto = caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * POST /api/synapse/cases/{caseId}/assign
     */
    @PostMapping("/{caseId}/assign")
    public ApiResponse<CaseDetailDto> assignCase(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long caseId,
            @Valid @RequestBody CaseAssignRequest request) {

        caseCommandService.assignCase(tenantId, caseId, request.getAssigneeUserId(),
                actorUserId, null, null, null);
        CaseDetailDto dto = caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * POST /api/synapse/cases/{caseId}/comment
     */
    @PostMapping("/{caseId}/comment")
    public ApiResponse<CaseDetailDto> addComment(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_AGENT_ID, required = false) String actorAgentId,
            @PathVariable Long caseId,
            @Valid @RequestBody CaseCommentRequest request) {

        caseCommandService.addComment(tenantId, caseId, request.getCommentText(),
                actorUserId, actorAgentId, null, null, null);
        CaseDetailDto dto = caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }
}
