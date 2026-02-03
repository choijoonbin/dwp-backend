package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.analytics.AnalyticsKpiDto;
import com.dwp.services.synapsex.service.analytics.AnalyticsKpiQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Phase 4 Analytics API
 */
@RestController
@RequestMapping("/synapse/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsKpiQueryService analyticsKpiQueryService;

    @GetMapping("/kpis")
    public ApiResponse<AnalyticsKpiDto> getKpis(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String dims) {
        AnalyticsKpiDto dto = analyticsKpiQueryService.getKpis(tenantId, from, to, dims);
        return ApiResponse.success(dto);
    }
}
