package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.analysis.AnalysisReplayGateRunRequest;
import com.dwp.services.synapsex.dto.analysis.AnalysisReplayGateRunResponse;
import com.dwp.services.synapsex.dto.analysis.AuraQualityMetricsResponse;
import com.dwp.services.synapsex.service.analysis.AuraQualityMetricsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/synapse/aura")
@RequiredArgsConstructor
public class AuraQualityController {

    private final AuraQualityMetricsService auraQualityMetricsService;

    @GetMapping("/quality-metrics")
    public ApiResponse<AuraQualityMetricsResponse> getQualityMetrics(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.success(auraQualityMetricsService.getQualityMetrics(tenantId, from, to));
    }

    @PostMapping("/analysis-replay-gate-runs")
    public ApiResponse<AnalysisReplayGateRunResponse> saveReplayGateRun(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody AnalysisReplayGateRunRequest request) {
        return ApiResponse.success(auraQualityMetricsService.saveReplayGateRun(tenantId, request));
    }

    @GetMapping("/analysis-replay-gate-runs/latest")
    public ApiResponse<AnalysisReplayGateRunResponse> getLatestReplayGateRun(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId) {
        AnalysisReplayGateRunResponse latest = auraQualityMetricsService.getLatestReplayGateRun(tenantId).orElse(null);
        return ApiResponse.success(latest);
    }
}
