package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.case_.CaseListRowDto;
import com.dwp.services.synapsex.dto.case_.CaseStatusUpdateRequest;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.service.case_.CaseQueryService.CaseListQuery;
import com.dwp.services.synapsex.service.case_.CaseCommandService;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Phase 2 Cases API
 */
@RestController
@RequestMapping("/synapse/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseQueryService caseQueryService;
    private final CaseCommandService caseCommandService;

    /**
     * A1) GET /api/synapse/cases
     */
    @GetMapping
    public ApiResponse<PageResponse<CaseListRowDto>> getCases(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String caseType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant detectedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant detectedTo,
            @RequestParam(required = false) String bukrs,
            @RequestParam(required = false) String belnr,
            @RequestParam(required = false) String gjahr,
            @RequestParam(required = false) String buzei,
            @RequestParam(required = false) Long partyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = CaseListQuery.builder()
                .status(status)
                .severity(severity)
                .caseType(caseType)
                .detectedFrom(detectedFrom)
                .detectedTo(detectedTo)
                .bukrs(bukrs)
                .belnr(belnr)
                .gjahr(gjahr)
                .buzei(buzei)
                .partyId(partyId)
                .page(page)
                .size(size)
                .sort(sort)
                .build();

        PageResponse<CaseListRowDto> result = caseQueryService.findCases(tenantId, query);
        return ApiResponse.success(result);
    }

    /**
     * A2) GET /api/synapse/cases/{caseId}
     */
    @GetMapping("/{caseId}")
    public ApiResponse<CaseDetailDto> getCaseDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId) {

        CaseDetailDto dto = caseQueryService.findCaseDetail(tenantId, caseId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "케이스를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
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
}
