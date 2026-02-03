package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.dto.anomaly.AnomalyListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.service.anomaly.AnomalyQueryService;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.case_.CaseQueryService;
import com.dwp.services.synapsex.service.scope.ScopeEnforcementService;
import com.dwp.services.synapsex.util.DrillDownParamUtil;
import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Phase 2 Anomalies API
 */
@RestController
@RequestMapping("/synapse/anomalies")
@RequiredArgsConstructor
public class AnomalyController {

    private final AnomalyQueryService anomalyQueryService;
    private final CaseQueryService caseQueryService;
    private final AuditWriter auditWriter;
    private final ScopeEnforcementService scopeEnforcementService;

    /**
     * B1) GET /api/synapse/anomalies
     */
    @GetMapping
    public ApiResponse<PageResponse<AnomalyListRowDto>> getAnomalies(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) String driverType,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String company,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "detectedAt") String sort,
            @RequestParam(defaultValue = "desc") String order) {

        var timeRange = DrillDownParamUtil.resolve(range, from, to);
        List<String> requestedCompany = DrillDownParamUtil.parseMulti(company);
        List<String> resolvedCompany = scopeEnforcementService.resolveCompanyFilter(tenantId, null, requestedCompany);

        var query = AnomalyQueryService.AnomalyListQuery.builder()
                .range(range)
                .detectedFrom(timeRange.from())
                .detectedTo(timeRange.to())
                .status(status)
                .severity(severity)
                .anomalyType(type != null ? type : (anomalyType != null ? anomalyType : driverType))
                .ids(ids != null ? DrillDownParamUtil.parseIds(ids) : List.of())
                .company(resolvedCompany.isEmpty() ? null : resolvedCompany)
                .page(page < 1 ? 0 : page - 1)
                .size(Math.min(200, Math.max(1, size)))
                .sort(sort)
                .order(order)
                .build();

        PageResponse<AnomalyListRowDto> result = anomalyQueryService.findAnomalies(tenantId, query);
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_ANOMALY_VIEW_LIST,
                "ANOMALY_LIST", null, AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, null, null, null, null, null, null);
        return ApiResponse.success(result);
    }

    /**
     * B2) GET /api/synapse/anomalies/{anomalyId}
     */
    @GetMapping("/{anomalyId}")
    public ApiResponse<CaseDetailDto> getAnomalyDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long anomalyId) {

        CaseDetailDto dto = caseQueryService.findCaseDetail(tenantId, anomalyId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "이상을 찾을 수 없습니다."));
        auditWriter.log(tenantId, AuditEventConstants.CATEGORY_ACTION, AuditEventConstants.TYPE_ANOMALY_VIEW_DETAIL,
                "ANOMALY", String.valueOf(anomalyId), AuditEventConstants.ACTOR_HUMAN, actorUserId, null, null, AuditEventConstants.CHANNEL_API,
                AuditEventConstants.OUTCOME_SUCCESS, AuditEventConstants.SEVERITY_INFO,
                null, null, null, null, null, null, null, null, null, null);
        return ApiResponse.success(dto);
    }
}
