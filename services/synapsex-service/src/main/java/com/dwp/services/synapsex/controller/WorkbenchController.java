package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.case_.CaseListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.workbench.CaseActionHistoryItemDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchCaseDetailResponseDto;
import com.dwp.services.synapsex.dto.workbench.WorkbenchNavigationDto;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import com.dwp.services.synapsex.service.case_.CaseQueryService.CaseListQuery;
import com.dwp.services.synapsex.service.scope.ScopeEnforcementService;
import com.dwp.services.synapsex.service.workbench.WorkbenchQueryService;
import com.dwp.services.synapsex.util.DrillDownParamUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Workbench API (Phase 2).
 * Gateway 경로: /api/v1/synapse/workbench
 * - 모든 조회에 tenant 격리 적용. ScopeEnforcementService로 company 필터 검증.
 */
@RestController
@RequestMapping("/synapse/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchQueryService workbenchQueryService;
    private final CaseQueryService caseQueryService;
    private final ScopeEnforcementService scopeEnforcementService;

    /**
     * GET /api/v1/synapse/workbench — 케이스 목록 (tenant 격리, scope 검증)
     */
    @GetMapping
    public ApiResponse<PageResponse<CaseListRowDto>> getWorkbenchList(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String caseType,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<String> requestedCompany = DrillDownParamUtil.parseMulti(company);
        List<String> resolvedCompany = scopeEnforcementService.resolveCompanyFilter(tenantId, null, requestedCompany);

        var query = CaseListQuery.builder()
                .status(status)
                .severity(severity)
                .caseType(caseType)
                .company(resolvedCompany.isEmpty() ? null : resolvedCompany)
                .detectedFrom(from)
                .detectedTo(to)
                .page(Math.max(0, page))
                .size(Math.min(100, Math.max(1, size)))
                .sort("detectedAt")
                .order("desc")
                .build();

        PageResponse<CaseListRowDto> result = caseQueryService.findCases(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * GET /api/v1/synapse/workbench/cases/{caseId} — Aggregator: 케이스 상세 + 최신 분석 + 타임라인(occurred_at DESC, 최근 50건)
     */
    @GetMapping("/cases/{caseId}")
    public ApiResponse<WorkbenchCaseDetailResponseDto> getCaseDetailWithTimeline(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId) {

        WorkbenchCaseDetailResponseDto body = workbenchQueryService.getCaseDetailWithTimeline(tenantId, caseId);
        return ApiResponse.success(body);
    }

    /**
     * GET /api/synapse/workbench/cases/{caseId}/history — 조치 이력 (agent_case_action_history, action_at DESC)
     */
    @GetMapping("/cases/{caseId}/history")
    public ApiResponse<List<com.dwp.services.synapsex.dto.workbench.CaseActionHistoryItemDto>> getCaseActionHistory(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId,
            @RequestParam(defaultValue = "50") int limit) {

        List<CaseActionHistoryItemDto> list = workbenchQueryService.getCaseActionHistory(tenantId, caseId, limit);
        return ApiResponse.success(list);
    }

    /**
     * GET /api/v1/synapse/workbench/navigation — 워크벤치 진입 시 관련 설정 메뉴 목록(deepLink 포함).
     * 규정 수정·정책 변경 등 기존 메뉴로 즉시 점프할 수 있는 정보 반환.
     */
    @GetMapping("/navigation")
    public ApiResponse<WorkbenchNavigationDto> getNavigation(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        WorkbenchNavigationDto body = workbenchQueryService.getNavigation(tenantId);
        return ApiResponse.success(body);
    }
}
