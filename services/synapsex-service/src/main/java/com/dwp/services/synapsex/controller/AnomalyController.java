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
import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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

    /**
     * B1) GET /api/synapse/anomalies
     */
    @GetMapping
    public ApiResponse<PageResponse<AnomalyListRowDto>> getAnomalies(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String anomalyType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant detectedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant detectedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        var query = AnomalyQueryService.AnomalyListQuery.builder()
                .severity(severity)
                .anomalyType(anomalyType)
                .detectedFrom(detectedFrom)
                .detectedTo(detectedTo)
                .page(page)
                .size(size)
                .sort(sort)
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
