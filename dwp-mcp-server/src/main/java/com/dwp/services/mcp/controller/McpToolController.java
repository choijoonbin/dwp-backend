package com.dwp.services.mcp.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.mcp.client.AuthServerPermissionClient;
import com.dwp.services.mcp.dto.mcp.*;
import com.dwp.services.mcp.service.McpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/mcp/tools")
@RequiredArgsConstructor
public class McpToolController {

    private static final String SCHEMA_VERSION = "mcp.v1";
    private static final String SOURCE_SYSTEM = "dwp-mcp-server";
    private static final String RESOURCE_KEY = "mcp.tools";
    private static final String PERM_POLICY_READ = "MCP_POLICY_READ";
    private static final String PERM_MASTERDATA_READ = "MCP_MASTERDATA_READ";
    private static final String PERM_EVAL_READ = "MCP_EVAL_READ";

    private final McpToolService mcpToolService;
    private final AuthServerPermissionClient permissionClient;
    @Value("${mcp.security.service-account-user-ids:}")
    private String serviceAccountUserIds;
    @Value("${mcp.security.service-account-tenant-ids:}")
    private String serviceAccountTenantIds;

    @PostMapping("/policy-regulation")
    public ApiResponse<McpToolEnvelope<PolicyLookupResult>> policyRegulation(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody PolicyLookupRequest request) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=policy-regulation traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<PolicyLookupResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (!hasPermission(tenantId, userId, PERM_POLICY_READ)) {
            envelope = error(t, "FORBIDDEN", "권한이 없습니다.");
        } else {
            PolicyLookupResult result = mcpToolService.policyLookup(tenantId, request);
            envelope = success(t,
                    result.getItems().stream().map(PolicyLookupResult.PolicyItem::getEffectiveFrom).filter(java.util.Objects::nonNull).min(java.util.Comparator.naturalOrder()).orElse(null),
                    result.getItems().stream().map(PolicyLookupResult.PolicyItem::getEffectiveTo).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElse(null),
                    result);
        }
        logResponseSummary("policy-regulation", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/business-calendar")
    public ApiResponse<McpToolEnvelope<BusinessCalendarResult>> businessCalendar(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody BusinessCalendarRequest request) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=business-calendar traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<BusinessCalendarResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (request.getUserId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "userId는 필수입니다.");
        } else {
            BusinessCalendarResult result = mcpToolService.businessCalendar(tenantId, request);
            envelope = success(t, result.getEventDate(), result.getEventDate(), result);
        }
        logResponseSummary("business-calendar", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/master-data")
    public ApiResponse<McpToolEnvelope<MasterDataNormalizeResult>> masterData(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody MasterDataNormalizeRequest request) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=master-data traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<MasterDataNormalizeResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (!hasPermission(tenantId, userId, PERM_MASTERDATA_READ)) {
            envelope = error(t, "FORBIDDEN", "권한이 없습니다.");
        } else if ((request.getMccCode() == null || request.getMccCode().isBlank())
                && (request.getExpenseType() == null || request.getExpenseType().isBlank())
                && (request.getHrStatus() == null || request.getHrStatus().isBlank())) {
            envelope = error(t, "INPUT_PARTIAL", "mccCode|expenseType|hrStatus 중 하나 이상 필요합니다.");
        } else {
            MasterDataNormalizeResult result = mcpToolService.masterData(tenantId, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("master-data", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/case-context")
    public ApiResponse<McpToolEnvelope<CaseContextResult>> caseContext(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody CaseContextRequest request) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = request.getCaseId();
        UUID runId = null;
        log.info("MCP request summary: tool=case-context traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<CaseContextResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (request.getUserId() == null && request.getCaseId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "userId 또는 caseId는 필수입니다.");
        } else {
            CaseContextResult result = mcpToolService.caseContext(tenantId, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("case-context", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/evidence-verification")
    public ApiResponse<McpToolEnvelope<EvidenceVerificationResult>> evidenceVerification(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody EvidenceVerificationRequest request) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = request.getCaseId();
        UUID runId = request.getRunId();
        log.info("MCP request summary: tool=evidence-verification traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<EvidenceVerificationResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (request.getCaseId() == null && request.getRunId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "caseId 또는 runId는 필수입니다.");
        } else {
            EvidenceVerificationResult result = mcpToolService.evidenceVerification(tenantId, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("evidence-verification", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/rag-conflict-diagnostics")
    public ApiResponse<McpToolEnvelope<RagConflictDiagnosticsResult>> ragConflictDiagnostics(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody RagConflictDiagnosticsRequest request) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = request.getCaseId();
        UUID runId = request.getRunId();
        log.info("MCP request summary: tool=rag-conflict-diagnostics traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<RagConflictDiagnosticsResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (request.getCaseId() == null && request.getRunId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "caseId 또는 runId는 필수입니다.");
        } else {
            RagConflictDiagnosticsResult result = mcpToolService.ragConflictDiagnostics(tenantId, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("rag-conflict-diagnostics", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @GetMapping("/eval-gate/latest")
    public ApiResponse<McpToolEnvelope<EvalGateLatestResult>> latestEvalGate(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId) {
        String t = resolveTraceId(traceId);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=eval-gate/latest traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<EvalGateLatestResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", "X-User-ID 헤더가 필요합니다.");
        } else if (!hasPermission(tenantId, userId, PERM_EVAL_READ)) {
            envelope = error(t, "FORBIDDEN", "권한이 없습니다.");
        } else {
            EvalGateLatestResult result = mcpToolService.latestEvalGate(tenantId);
            envelope = result == null
                    ? error(t, "EVIDENCE_MISSING", "최신 RAG 평가 결과가 없습니다.")
                    : success(t, null, null, result);
        }
        logResponseSummary("eval-gate/latest", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    private boolean hasPermission(Long tenantId, Long userId, String permissionCode) {
        if (isServiceAccountAllowed(tenantId, userId)) {
            return true;
        }
        try {
            var res = permissionClient.check(tenantId, userId, RESOURCE_KEY, permissionCode);
            if (res != null && Boolean.TRUE.equals(res.getData())) return true;
        } catch (Exception e) {
            log.warn("MCP permission check failed tenantId={} userId={} permissionCode={}: {}",
                    tenantId, userId, permissionCode, e.getMessage());
        }
        try {
            var admin = permissionClient.isAdmin(tenantId, userId);
            return admin != null && Boolean.TRUE.equals(admin.getData());
        } catch (Exception e) {
            log.warn("MCP admin fallback check failed tenantId={} userId={}: {}", tenantId, userId, e.getMessage());
            return false;
        }
    }

    private String resolveTraceId(String traceId) {
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }

    private <T> McpToolEnvelope<T> success(String traceId, LocalDate effectiveFrom, LocalDate effectiveTo, T data) {
        return McpToolEnvelope.success(SCHEMA_VERSION, SOURCE_SYSTEM, traceId, effectiveFrom, effectiveTo, data);
    }

    private <T> McpToolEnvelope<T> error(String traceId, String code, String message) {
        return McpToolEnvelope.error(SCHEMA_VERSION, SOURCE_SYSTEM, traceId, code, message);
    }

    private boolean isServiceAccountAllowed(Long tenantId, Long userId) {
        Set<Long> allowUsers = parseLongSet(serviceAccountUserIds);
        if (!allowUsers.contains(userId)) return false;
        Set<Long> allowTenants = parseLongSet(serviceAccountTenantIds);
        return allowTenants.isEmpty() || allowTenants.contains(tenantId);
    }

    private Set<Long> parseLongSet(String csv) {
        Set<Long> result = new HashSet<>();
        if (csv == null || csv.isBlank()) return result;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(s -> {
                    try {
                        result.add(Long.parseLong(s));
                    } catch (NumberFormatException ignore) {
                    }
                });
        return result;
    }

    private void logResponseSummary(String tool, String traceId, Long tenantId, Long userId,
                                    Long caseId, UUID runId, McpToolEnvelope<?> envelope, long startedAt) {
        String decisionCode = "OK";
        if (!Boolean.TRUE.equals(envelope.getSuccess())) {
            decisionCode = envelope.getErrorCode();
        } else if (envelope.getData() instanceof EvidenceVerificationResult evidence) {
            decisionCode = evidence.getDecisionCode();
        } else if (envelope.getData() instanceof RagConflictDiagnosticsResult conflict) {
            decisionCode = conflict.getDecisionCode();
        }
        long latency = System.currentTimeMillis() - startedAt;
        log.info("MCP response summary: tool={} traceId={} tenantId={} userId={} caseId={} runId={} success={} decisionCode={} latency_ms={}",
                tool, traceId, tenantId, userId, caseId, runId, envelope.getSuccess(), decisionCode, latency);
    }
}
