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
        if ("FAILED".equals(payload.getStatus())) {
            log.warn("Aura callback FAILED: runId={} error={}", payload.getRunId(), payload.getError());
            log.debug("Aura callback FAILED payload summary: runId={} caseId={} status={} hasError={} hasAuraTraceId={} hasAnalysis={} hasFinalResult={} proposalsSize={} partialEventsSize={}",
                    payload.getRunId(),
                    payload.getCaseId(),
                    payload.getStatus(),
                    payload.getError() != null && !payload.getError().isNull(),
                    payload.getAuraTraceId() != null && !payload.getAuraTraceId().isBlank(),
                    payload.getAnalysis() != null,
                    payload.getFinalResult() != null,
                    payload.getProposals() != null ? payload.getProposals().size() : 0,
                    payload.getPartialEvents() != null ? payload.getPartialEvents().size() : 0);
        } else {
            log.debug("Aura callback: runId={} status={}", payload.getRunId(), payload.getStatus());
        }
        caseAnalysisService.handleAuraCallback(payload);
        return ApiResponse.success(null);
    }
}
