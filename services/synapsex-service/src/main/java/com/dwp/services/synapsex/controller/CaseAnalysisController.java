package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.analysis.*;
import com.dwp.services.synapsex.service.analysis.AnalysisStreamProxyService;
import com.dwp.services.synapsex.service.analysis.CaseAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
@RestController
@RequestMapping("/synapse")
@RequiredArgsConstructor
public class CaseAnalysisController {

    private final CaseAnalysisService caseAnalysisService;
    private final AnalysisStreamProxyService analysisStreamProxyService;
    private final ObjectMapper objectMapper;

    @Value("${synapse.demo-mode:false}")
    private boolean demoMode;

    /**
     * (1) 분석 트리거 — Phase2 202 표준: 항상 202 Accepted
     * POST /api/synapse/cases/{caseId}/analysis-runs
     */
    @PostMapping("/cases/{caseId}/analysis-runs")
    public ResponseEntity<ApiResponse<AnalysisRunTriggerResponse>> triggerAnalysis(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("caseId") Long caseId,
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
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("caseId") Long caseId,
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
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("runId") UUID runId) {
        AnalysisRunStatusDto dto = caseAnalysisService.getRunStatus(tenantId, runId);
        return ApiResponse.success(dto);
    }

    /**
     * (3) 분석 스트림 (SSE) — 옵션 B: BE 프록시로 Aura 스트림 중계. demo 모드 시 폴링 목.
     * GET /api/synapse/analysis-runs/{runId}/stream?caseId= (caseId 선택, 검증용)
     */
    @GetMapping(value = "/analysis-runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.SseEmitter> streamRun(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = HeaderConstants.X_SANDBOX, required = false) String sandboxHeader,
            @PathVariable("runId") UUID runId,
            @RequestParam(value = "caseId", required = false) Long caseId) {
        boolean sandbox = "true".equalsIgnoreCase(sandboxHeader != null ? sandboxHeader.trim() : "");
        log.info("SSE stream request received: runId={} caseId={} sandbox={} (suspected disconnect trace)", runId, caseId, sandbox);
        if (!demoMode) {
            try {
                org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                        analysisStreamProxyService.streamFromAura(tenantId, runId, caseId, authorization, sandbox);
                log.debug("SSE stream returning emitter to client: runId={}", runId);
                return ResponseEntity.ok()
                        .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header(org.springframework.http.HttpHeaders.CONNECTION, "keep-alive")
                        .header("X-Accel-Buffering", "no")
                        .body(emitter);
            } catch (com.dwp.core.exception.BaseException e) {
                // Accept: text/event-stream 요청에 JSON 예외 응답 시 HttpMediaTypeNotAcceptableException 발생 → 500 빈 body.
                // SSE 형식으로 failed 이벤트 1회 전송 후 완료.
                log.warn("SSE stream pre-start error: runId={} errorCode={} message={}", runId, e.getErrorCode(), e.getMessage());
                org.springframework.web.servlet.mvc.method.annotation.SseEmitter failedEmitter =
                        analysisStreamProxyService.createFailedEmitter(runId, e.getMessage());
                return ResponseEntity.ok()
                        .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header(org.springframework.http.HttpHeaders.CONNECTION, "keep-alive")
                        .header("X-Accel-Buffering", "no")
                        .body(failedEmitter);
            }
        }
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
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(org.springframework.http.HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
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
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("caseId") Long caseId,
            @RequestParam(value = "runId", required = false) UUID runId) {
        List<CaseActionProposalDto> list = caseAnalysisService.getActionProposals(tenantId, caseId, runId);
        return ApiResponse.success(list);
    }

    /**
     * (5b) FE 요청: 단일 decision API
     * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/decision
     * Body: { "decision": "APPROVE" | "REJECT", "comment"?: string }
     */
    @PostMapping("/cases/{caseId}/action-proposals/{proposalId}/decision")
    public ApiResponse<Void> decisionProposal(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable("caseId") Long caseId,
            @PathVariable("proposalId") UUID proposalId,
            @RequestBody(required = false) ProposalDecisionBodyDto body) {
        String comment = body != null ? body.getComment() : null;
        if (body != null && "REJECT".equalsIgnoreCase(body.getDecision())) {
            caseAnalysisService.rejectProposal(tenantId, proposalId, userId, comment);
        } else {
            caseAnalysisService.approveProposal(tenantId, proposalId, userId, comment);
        }
        return ApiResponse.success(null);
    }

    /**
     * (6) 액션 제안 승인
     * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/approve
     * Body(optional): { "comment": "..." }
     */
    @PostMapping("/cases/{caseId}/action-proposals/{proposalId}/approve")
    public ApiResponse<Void> approveProposal(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable("caseId") Long caseId,
            @PathVariable("proposalId") UUID proposalId,
            @RequestBody(required = false) ProposalDecisionRequest body) {
        String comment = body != null ? body.getComment() : null;
        caseAnalysisService.approveProposal(tenantId, proposalId, userId, comment);
        return ApiResponse.success(null);
    }

    /**
     * (7) 액션 제안 거절
     * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/reject
     * Body(optional): { "comment": "..." }
     */
    @PostMapping("/cases/{caseId}/action-proposals/{proposalId}/reject")
    public ApiResponse<Void> rejectProposal(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable("caseId") Long caseId,
            @PathVariable("proposalId") UUID proposalId,
            @RequestBody(required = false) ProposalDecisionRequest body) {
        String comment = body != null ? body.getComment() : null;
        caseAnalysisService.rejectProposal(tenantId, proposalId, userId, comment);
        return ApiResponse.success(null);
    }

    /**
     * (7b) FE 요청: body로 proposalId 전달
     * POST /api/synapse/cases/{caseId}/actions/execute
     * Body: { "proposalId": UUID, "runId"?: UUID, "mode"?: "SIMULATION" }
     */
    @PostMapping("/cases/{caseId}/actions/execute")
    public ApiResponse<ProposalExecuteResponseDto> executeAction(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable("caseId") Long caseId,
            @RequestBody ExecuteActionRequestDto body) {
        if (body == null || body.getProposalId() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "proposalId는 필수입니다.");
        }
        ProposalExecuteResponseDto result = caseAnalysisService.executeProposal(tenantId, caseId, body.getProposalId(), userId,
                body.getGatewayRequestId(), null, null);
        return ApiResponse.success(result);
    }

    /**
     * (8) Phase3: 액션 제안 실행(시뮬레이션)
     * POST /api/synapse/cases/{caseId}/action-proposals/{proposalId}/execute
     * Body(선택): { runId?, simulate?, gatewayRequestId? } — FE가 runId·simulate·gatewayRequestId 전송 시 멱등·검증에 활용.
     * APPROVED 제안만 실행 가능. 결과는 case_action_execution에 저장, ACTION_EXECUTE_SIM 감사.
     */
    @PostMapping("/cases/{caseId}/action-proposals/{proposalId}/execute")
    public ApiResponse<ProposalExecuteResponseDto> executeProposal(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @PathVariable("caseId") Long caseId,
            @PathVariable("proposalId") UUID proposalId,
            @RequestBody(required = false) ExecuteProposalPathBodyDto body) {
        String gatewayRequestId = body != null && body.getGatewayRequestId() != null && !body.getGatewayRequestId().isBlank()
                ? body.getGatewayRequestId() : null;
        UUID runIdForValidation = body != null ? body.getRunId() : null;
        ProposalExecuteResponseDto result = caseAnalysisService.executeProposal(tenantId, caseId, proposalId, userId, gatewayRequestId, null, runIdForValidation);
        return ApiResponse.success(result);
    }

    /**
     * (9) Phase3 표준: POST /api/synapse/actions/execute (body에 caseId 포함)
     * 권장 A: proposalId로 실행. 대안 B: actionType+payload로 실행.
     */
    @PostMapping("/actions/execute")
    public ApiResponse<ProposalExecuteResponseDto> executeActionUnified(
            @RequestHeader(name = HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestBody ExecuteActionRequestDto body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "요청 본문이 필요합니다.");
        }
        ProposalExecuteResponseDto result = caseAnalysisService.executeAction(tenantId, body, userId);
        return ApiResponse.success(result);
    }
}
