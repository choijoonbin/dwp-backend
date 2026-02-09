package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.analysis.*;
import com.dwp.services.synapsex.service.analysis.CaseAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase2: 케이스 분석 실행, 결과, 액션 제안 API
 * 202 표준: POST analysis-runs는 항상 202 Accepted
 */
@RestController
@RequestMapping("/synapse")
@RequiredArgsConstructor
public class CaseAnalysisController {

    private final CaseAnalysisService caseAnalysisService;
    private final ObjectMapper objectMapper;

    /**
     * (1) 분석 트리거 — Phase2 202 표준: 항상 202 Accepted
     * POST /api/synapse/cases/{caseId}/analysis-runs
     */
    @PostMapping("/cases/{caseId}/analysis-runs")
    public ResponseEntity<ApiResponse<AnalysisRunTriggerResponse>> triggerAnalysis(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long caseId,
            @RequestBody(required = false) AnalysisRunTriggerRequest request) {
        AnalysisRunTriggerResponse res = caseAnalysisService.triggerAnalysis(tenantId, caseId, request, userId, authorization);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(res));
    }

    /**
     * (1b) 케이스별 분석 실행 목록 — Phase2 latest 편의
     * GET /api/synapse/cases/{caseId}/analysis-runs?latest=true → 최신 runId
     */
    @GetMapping("/cases/{caseId}/analysis-runs")
    public ApiResponse<Object> getAnalysisRuns(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId,
            @RequestParam(value = "latest", required = false) Boolean latest) {
        Object res = caseAnalysisService.getAnalysisRuns(tenantId, caseId, Boolean.TRUE.equals(latest));
        return ApiResponse.success(res);
    }

    /**
     * (2) 분석 실행 상태 조회
     * GET /api/synapse/analysis-runs/{runId}
     */
    @GetMapping("/analysis-runs/{runId}")
    public ApiResponse<AnalysisRunStatusDto> getRunStatus(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable UUID runId) {
        AnalysisRunStatusDto dto = caseAnalysisService.getRunStatus(tenantId, runId);
        return ApiResponse.success(dto);
    }

    /**
     * (3) 분석 스트림 (SSE) — 최소 started/completed/failed
     * GET /api/synapse/analysis-runs/{runId}/stream
     */
    @GetMapping(value = "/analysis-runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> streamRun(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable UUID runId) {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(300_000L);
        String runIdStr = runId.toString();
        new java.util.Timer().schedule(new java.util.TimerTask() {
            int count = 0;

            @Override
            public void run() {
                try {
                    if (count == 0) {
                        String startedData = toJson(Map.of("status", "started", "runId", runIdStr));
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("started").data(startedData));
                        count++;
                        return;
                    }
                    if (count == 1) {
                        String step1Data = toJson(Map.of("label", "Normalize evidence", "percent", 20, "detail", ""));
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("step").data(step1Data));
                        count++;
                        return;
                    }
                    if (count == 2) {
                        String step2Data = toJson(Map.of("label", "Scoring", "percent", 60, "detail", ""));
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("step").data(step2Data));
                        count++;
                        return;
                    }
                    AnalysisRunStatusDto status = caseAnalysisService.getRunStatus(tenantId, runId);
                    if ("COMPLETED".equals(status.getStatus())) {
                        String completedData = toJson(Map.of("status", "completed", "runId", runIdStr));
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("completed").data(completedData));
                        emitter.complete();
                        cancel();
                    } else if ("FAILED".equals(status.getStatus())) {
                        String message = status.getError() != null ? status.getError() : "";
                        String failedData = toJson(Map.of("status", "failed", "runId", runIdStr, "message", message));
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                .name("failed").data(failedData));
                        emitter.complete();
                        cancel();
                    } else if (count++ > 60) {
                        emitter.complete();
                        cancel();
                    }
                } catch (Exception e) {
                    emitter.completeWithError(e);
                    cancel();
                }
            }
        }, 0, 500);
        return ResponseEntity.ok(emitter);
    }

    private String toJson(Map<String, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * (5) 액션 제안 목록 — runId 기준 필터 지원
     * GET /api/synapse/cases/{caseId}/action-proposals?runId={runId}
     */
    @GetMapping("/cases/{caseId}/action-proposals")
    public ApiResponse<List<CaseActionProposalDto>> getActionProposals(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long caseId,
            @RequestParam(required = false) UUID runId) {
        List<CaseActionProposalDto> list = caseAnalysisService.getActionProposals(tenantId, caseId, runId);
        return ApiResponse.success(list);
    }

    /**
     * (6) 액션 제안 승인
     * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/approve
     */
    @PostMapping("/cases/{caseId}/action-proposals/{proposalId}/approve")
    public ApiResponse<Void> approveProposal(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable Long caseId,
            @PathVariable UUID proposalId) {
        caseAnalysisService.approveProposal(tenantId, proposalId, userId);
        return ApiResponse.success(null);
    }

    /**
     * (7) 액션 제안 거절
     * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/reject
     */
    @PostMapping("/cases/{caseId}/action-proposals/{proposalId}/reject")
    public ApiResponse<Void> rejectProposal(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable Long caseId,
            @PathVariable UUID proposalId) {
        caseAnalysisService.rejectProposal(tenantId, proposalId, userId);
        return ApiResponse.success(null);
    }
}
