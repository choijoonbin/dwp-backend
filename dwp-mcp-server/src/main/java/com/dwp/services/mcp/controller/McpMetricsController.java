package com.dwp.services.mcp.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.mcp.dto.mcp.McpToolEnvelope;
import com.dwp.services.mcp.dto.mcp.ShadowCompareResult;
import com.dwp.services.mcp.dto.mcp.ShadowRunMetadataRequest;
import com.dwp.services.mcp.dto.mcp.ShadowRunMetadataResult;
import com.dwp.services.mcp.service.McpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/mcp/metrics")
@RequiredArgsConstructor
public class McpMetricsController {

    private static final String SCHEMA_VERSION = "mcp.v1";
    private static final String SOURCE_SYSTEM = "dwp-mcp-server";

    private final McpToolService mcpToolService;

    @PostMapping("/shadow-runs")
    public ResponseEntity<ApiResponse<McpToolEnvelope<ShadowRunMetadataResult>>> saveShadowRunMetadata(
            @RequestHeader(value = HeaderConstants.X_TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody ShadowRunMetadataRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        if (tenantId == null) return badRequest(t, "X-Tenant-ID 헤더가 필요합니다.");
        if (userId == null) return badRequest(t, invalidUserIdMessage(userIdHeader));
        if (request.getRunId() == null) return badRequest(t, "runId는 필수입니다.");
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=metrics-shadow-runs traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, request.getCaseId(), request.getRunId());

        ShadowRunMetadataResult result = mcpToolService.saveShadowRunMetadata(tenantId, request);
        McpToolEnvelope<ShadowRunMetadataResult> envelope =
                McpToolEnvelope.success(SCHEMA_VERSION, SOURCE_SYSTEM, t, null, null, result);
        finalizeEnvelope(envelope, result.getDecisionCode(), result.getEvidenceRefs(), started);

        log.info("MCP response summary: tool=metrics-shadow-runs traceId={} tenantId={} userId={} caseId={} runId={} success={} decisionCode={} latency_ms={}",
                t, tenantId, userId, request.getCaseId(), request.getRunId(), envelope.getSuccess(), envelope.getDecisionCode(),
                envelope.getMeta() != null ? envelope.getMeta().getLatencyMs() : null);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @GetMapping("/shadow-compare")
    public ResponseEntity<ApiResponse<McpToolEnvelope<ShadowCompareResult>>> shadowCompare(
            @RequestHeader(value = HeaderConstants.X_TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        if (tenantId == null) return badRequest(t, "X-Tenant-ID 헤더가 필요합니다.");
        if (userId == null) return badRequest(t, invalidUserIdMessage(userIdHeader));
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=metrics-shadow-compare traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, null, null);

        ShadowCompareResult result = mcpToolService.shadowCompare(tenantId, from, to);
        McpToolEnvelope<ShadowCompareResult> envelope =
                McpToolEnvelope.success(SCHEMA_VERSION, SOURCE_SYSTEM, t, null, null, result);
        finalizeEnvelope(envelope, result.getDecisionCode(), result.getEvidenceRefs(), started);

        log.info("MCP response summary: tool=metrics-shadow-compare traceId={} tenantId={} userId={} caseId={} runId={} success={} decisionCode={} latency_ms={}",
                t, tenantId, userId, null, null, envelope.getSuccess(), envelope.getDecisionCode(),
                envelope.getMeta() != null ? envelope.getMeta().getLatencyMs() : null);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    private String resolveTraceId(String traceId) {
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }

    private <T> void finalizeEnvelope(McpToolEnvelope<T> envelope, String decisionCode, List<String> evidenceRefs, long startedAt) {
        long latency = System.currentTimeMillis() - startedAt;
        envelope.setDecisionCode(decisionCode != null ? decisionCode : "OK");
        envelope.setEvidenceRefs(evidenceRefs != null ? evidenceRefs : List.of());
        envelope.setMeta(McpToolEnvelope.Meta.builder().latencyMs(latency).build());
    }

    private <T> ResponseEntity<ApiResponse<McpToolEnvelope<T>>> badRequest(String traceId, String message) {
        McpToolEnvelope<T> envelope = McpToolEnvelope.error(SCHEMA_VERSION, SOURCE_SYSTEM, traceId,
                "INPUT_PARTIAL", message, "INPUT_PARTIAL", List.of(), 0L);
        ApiResponse<McpToolEnvelope<T>> body = ApiResponse.<McpToolEnvelope<T>>builder()
                .status("ERROR")
                .message(message)
                .data(envelope)
                .errorCode("INPUT_PARTIAL")
                .success(false)
                .timestamp(LocalDateTime.now())
                .traceId(traceId)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private Long parseUserIdOrNull(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) return null;
        try {
            return Long.parseLong(userIdHeader.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String invalidUserIdMessage(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return "X-User-ID 헤더가 필요합니다.";
        }
        return "X-User-ID 헤더는 숫자(Long)여야 합니다.";
    }
}
