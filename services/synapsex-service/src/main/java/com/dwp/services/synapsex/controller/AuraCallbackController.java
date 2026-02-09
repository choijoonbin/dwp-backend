package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.synapsex.dto.analysis.AuraCallbackPayload;
import com.dwp.services.synapsex.service.analysis.CaseAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Aura → BE 콜백 (내부)
 * POST /api/synapse/internal/aura/callback
 */
@Slf4j
@RestController
@RequestMapping("/synapse/internal/aura")
@RequiredArgsConstructor
public class AuraCallbackController {

    private final CaseAnalysisService caseAnalysisService;

    @PostMapping("/callback")
    public ApiResponse<Void> handleCallback(@RequestBody AuraCallbackPayload payload) {
        log.debug("Aura callback: runId={} status={}", payload.getRunId(), payload.getStatus());
        caseAnalysisService.handleAuraCallback(payload);
        return ApiResponse.success(null);
    }
}
