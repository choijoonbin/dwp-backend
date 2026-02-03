package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.anomaly.AnomalyListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.service.anomaly.AnomalyQueryService;
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

    /**
     * B1) GET /api/synapse/anomalies
     */
    @GetMapping
    public ApiResponse<PageResponse<AnomalyListRowDto>> getAnomalies(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
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
        return ApiResponse.success(result);
    }
}
