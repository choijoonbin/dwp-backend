package com.dwp.services.mcp.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.mcp.client.AuthServerPermissionClient;
import com.dwp.services.mcp.dto.mcp.*;
import com.dwp.services.mcp.service.McpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody PolicyLookupRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
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

    // v2 strict mode: header/body validation failures -> HTTP 400
    @PostMapping("/v2/policy-regulation")
    public ResponseEntity<ApiResponse<McpToolEnvelope<PolicyLookupResult>>> policyRegulationV2(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody PolicyLookupRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=policy-regulation(v2) traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, null, null);
        if (userId == null) return badRequest("policy-regulation(v2)", t, tenantId, null, null, null, invalidUserIdMessage(userIdHeader), started, "X-User-ID");
        if (!hasPermission(tenantId, userId, PERM_POLICY_READ)) {
            McpToolEnvelope<PolicyLookupResult> envelope = error(t, "FORBIDDEN", "권한이 없습니다.");
            logResponseSummary("policy-regulation(v2)", t, tenantId, userId, null, null, envelope, started);
            return ResponseEntity.ok(ApiResponse.success(envelope));
        }
        PolicyLookupResult result = mcpToolService.policyLookup(tenantId, request);
        McpToolEnvelope<PolicyLookupResult> envelope = success(t,
                result.getItems().stream().map(PolicyLookupResult.PolicyItem::getEffectiveFrom).filter(java.util.Objects::nonNull).min(java.util.Comparator.naturalOrder()).orElse(null),
                result.getItems().stream().map(PolicyLookupResult.PolicyItem::getEffectiveTo).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElse(null),
                result);
        logResponseSummary("policy-regulation(v2)", t, tenantId, userId, null, null, envelope, started);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @PostMapping("/business-calendar")
    public ApiResponse<McpToolEnvelope<BusinessCalendarResult>> businessCalendar(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody BusinessCalendarRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=business-calendar traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<BusinessCalendarResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", invalidUserIdMessage(userIdHeader));
        } else if (request.getUserId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "userId는 필수입니다.");
        } else {
            BusinessCalendarResult result = mcpToolService.businessCalendar(tenantId, request);
            envelope = success(t, result.getEventDate(), result.getEventDate(), result);
        }
        logResponseSummary("business-calendar", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/v2/business-calendar")
    public ResponseEntity<ApiResponse<McpToolEnvelope<BusinessCalendarResult>>> businessCalendarV2(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody BusinessCalendarRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=business-calendar(v2) traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, null, null);
        if (userId == null) return badRequest("business-calendar(v2)", t, tenantId, null, null, null, invalidUserIdMessage(userIdHeader), started, "X-User-ID");
        if (request.getUserId() == null) return badRequest("business-calendar(v2)", t, tenantId, userId, null, null, "userId는 필수입니다.", started, "userId");
        BusinessCalendarResult result = mcpToolService.businessCalendar(tenantId, request);
        McpToolEnvelope<BusinessCalendarResult> envelope = success(t, result.getEventDate(), result.getEventDate(), result);
        logResponseSummary("business-calendar(v2)", t, tenantId, userId, null, null, envelope, started);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @PostMapping("/master-data")
    public ApiResponse<McpToolEnvelope<MasterDataNormalizeResult>> masterData(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody MasterDataNormalizeRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=master-data traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<MasterDataNormalizeResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", invalidUserIdMessage(userIdHeader));
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

    @PostMapping("/v2/master-data")
    public ResponseEntity<ApiResponse<McpToolEnvelope<MasterDataNormalizeResult>>> masterDataV2(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody MasterDataNormalizeRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=master-data(v2) traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, null, null);
        if (userId == null) return badRequest("master-data(v2)", t, tenantId, null, null, null, invalidUserIdMessage(userIdHeader), started, "X-User-ID");
        if (!hasPermission(tenantId, userId, PERM_MASTERDATA_READ)) {
            McpToolEnvelope<MasterDataNormalizeResult> envelope = error(t, "FORBIDDEN", "권한이 없습니다.");
            logResponseSummary("master-data(v2)", t, tenantId, userId, null, null, envelope, started);
            return ResponseEntity.ok(ApiResponse.success(envelope));
        }
        if ((request.getMccCode() == null || request.getMccCode().isBlank())
                && (request.getExpenseType() == null || request.getExpenseType().isBlank())
                && (request.getHrStatus() == null || request.getHrStatus().isBlank())) {
            return badRequest("master-data(v2)", t, tenantId, userId, null, null, "mccCode|expenseType|hrStatus 중 하나 이상 필요합니다.", started,
                    "mccCode", "expenseType", "hrStatus");
        }
        MasterDataNormalizeResult result = mcpToolService.masterData(tenantId, request);
        McpToolEnvelope<MasterDataNormalizeResult> envelope = success(t, null, null, result);
        logResponseSummary("master-data(v2)", t, tenantId, userId, null, null, envelope, started);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @PostMapping("/case-context")
    public ApiResponse<McpToolEnvelope<CaseContextResult>> caseContext(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody CaseContextRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        Long caseId = request.getCaseId();
        UUID runId = null;
        log.info("MCP request summary: tool=case-context traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<CaseContextResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", invalidUserIdMessage(userIdHeader));
        } else if (request.getUserId() == null && request.getCaseId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "userId 또는 caseId는 필수입니다.");
        } else {
            CaseContextResult result = mcpToolService.caseContext(tenantId, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("case-context", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/v2/case-context")
    public ResponseEntity<ApiResponse<McpToolEnvelope<CaseContextResult>>> caseContextV2(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody CaseContextRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=case-context(v2) traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, request.getCaseId(), null);
        if (userId == null) return badRequest("case-context(v2)", t, tenantId, null, request.getCaseId(), null, invalidUserIdMessage(userIdHeader), started, "X-User-ID");
        if (request.getUserId() == null && request.getCaseId() == null && (request.getDocKey() == null || request.getDocKey().isBlank())) {
            return badRequest("case-context(v2)", t, tenantId, userId, request.getCaseId(), null, "userId 또는 caseId 또는 docKey는 필수입니다.", started,
                    "userId", "caseId", "docKey");
        }
        CaseContextResult result = mcpToolService.caseContext(tenantId, request);
        McpToolEnvelope<CaseContextResult> envelope = success(t, null, null, result);
        logResponseSummary("case-context(v2)", t, tenantId, userId, request.getCaseId(), null, envelope, started);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @PostMapping("/evidence-verification")
    public ApiResponse<McpToolEnvelope<EvidenceVerificationResult>> evidenceVerification(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody EvidenceVerificationRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        Long caseId = request.getCaseId();
        UUID runId = request.getRunId();
        log.info("MCP request summary: tool=evidence-verification traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<EvidenceVerificationResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", invalidUserIdMessage(userIdHeader));
        } else if (request.getCaseId() == null && request.getRunId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "caseId 또는 runId는 필수입니다.");
        } else {
            EvidenceVerificationResult result = mcpToolService.evidenceVerification(tenantId, t, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("evidence-verification", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/v2/evidence-verification")
    public ResponseEntity<ApiResponse<McpToolEnvelope<EvidenceVerificationResult>>> evidenceVerificationV2(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody EvidenceVerificationRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=evidence-verification(v2) traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, request.getCaseId(), request.getRunId());
        if (userId == null) return badRequest("evidence-verification(v2)", t, tenantId, null, request.getCaseId(), request.getRunId(), invalidUserIdMessage(userIdHeader), started, "X-User-ID");
        if (request.getCaseId() == null && request.getRunId() == null && request.getSentenceCitationMap() == null) {
            return badRequest("evidence-verification(v2)", t, tenantId, userId, request.getCaseId(), request.getRunId(), "caseId 또는 runId 또는 sentenceCitationMap은 필수입니다.", started,
                    "caseId", "runId", "sentenceCitationMap");
        }
        EvidenceVerificationResult result = mcpToolService.evidenceVerification(tenantId, t, request);
        McpToolEnvelope<EvidenceVerificationResult> envelope = success(t, null, null, result);
        logResponseSummary("evidence-verification(v2)", t, tenantId, userId, request.getCaseId(), request.getRunId(), envelope, started);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @PostMapping("/rag-conflict-diagnostics")
    public ApiResponse<McpToolEnvelope<RagConflictDiagnosticsResult>> ragConflictDiagnostics(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody RagConflictDiagnosticsRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        Long caseId = request.getCaseId();
        UUID runId = request.getRunId();
        log.info("MCP request summary: tool=rag-conflict-diagnostics traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<RagConflictDiagnosticsResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", invalidUserIdMessage(userIdHeader));
        } else if (request.getCaseId() == null && request.getRunId() == null) {
            envelope = error(t, "INPUT_PARTIAL", "caseId 또는 runId는 필수입니다.");
        } else {
            RagConflictDiagnosticsResult result = mcpToolService.ragConflictDiagnostics(tenantId, request);
            envelope = success(t, null, null, result);
        }
        logResponseSummary("rag-conflict-diagnostics", t, tenantId, userId, caseId, runId, envelope, started);
        return ApiResponse.success(envelope);
    }

    @PostMapping("/v2/rag-conflict-diagnostics")
    public ResponseEntity<ApiResponse<McpToolEnvelope<RagConflictDiagnosticsResult>>> ragConflictDiagnosticsV2(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody RagConflictDiagnosticsRequest request) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        log.info("MCP request summary: tool=rag-conflict-diagnostics(v2) traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, request.getCaseId(), request.getRunId());
        if (userId == null) return badRequest("rag-conflict-diagnostics(v2)", t, tenantId, null, request.getCaseId(), request.getRunId(), invalidUserIdMessage(userIdHeader), started, "X-User-ID");
        if (request.getCaseId() == null && request.getRunId() == null) {
            return badRequest("rag-conflict-diagnostics(v2)", t, tenantId, userId, request.getCaseId(), request.getRunId(), "caseId 또는 runId는 필수입니다.", started, "caseId", "runId");
        }
        RagConflictDiagnosticsResult result = mcpToolService.ragConflictDiagnostics(tenantId, request);
        McpToolEnvelope<RagConflictDiagnosticsResult> envelope = success(t, null, null, result);
        logResponseSummary("rag-conflict-diagnostics(v2)", t, tenantId, userId, request.getCaseId(), request.getRunId(), envelope, started);
        return ResponseEntity.ok(ApiResponse.success(envelope));
    }

    @GetMapping("/eval-gate/latest")
    public ApiResponse<McpToolEnvelope<EvalGateLatestResult>> latestEvalGate(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) String userIdHeader,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId) {
        String t = resolveTraceId(traceId);
        Long userId = parseUserIdOrNull(userIdHeader);
        long started = System.currentTimeMillis();
        Long caseId = null;
        UUID runId = null;
        log.info("MCP request summary: tool=eval-gate/latest traceId={} tenantId={} userId={} caseId={} runId={}",
                t, tenantId, userId, caseId, runId);
        McpToolEnvelope<EvalGateLatestResult> envelope;
        if (userId == null) {
            envelope = error(t, "INPUT_PARTIAL", invalidUserIdMessage(userIdHeader));
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
        envelope.setDecisionCode(decisionCode);
        if (envelope.getMeta() == null) {
            envelope.setMeta(McpToolEnvelope.Meta.builder().latencyMs(latency).build());
        } else {
            envelope.getMeta().setLatencyMs(latency);
        }
        if (envelope.getEvidenceRefs() == null || envelope.getEvidenceRefs().isEmpty()) {
            envelope.setEvidenceRefs(extractEvidenceRefs(envelope.getData()));
        }
        log.info("MCP response summary: tool={} traceId={} tenantId={} userId={} caseId={} runId={} success={} decisionCode={} latency_ms={} http_status={}",
                tool, traceId, tenantId, userId, caseId, runId, envelope.getSuccess(), decisionCode, latency, 200);
    }

    private List<String> extractEvidenceRefs(Object data) {
        if (data instanceof CaseContextResult r && r.getEvidenceRefs() != null) return r.getEvidenceRefs();
        if (data instanceof EvidenceVerificationResult r && r.getEvidenceRefs() != null) return r.getEvidenceRefs();
        if (data instanceof RagConflictDiagnosticsResult r && r.getEvidenceRefs() != null) return r.getEvidenceRefs();
        if (data instanceof ShadowCompareResult r && r.getEvidenceRefs() != null) return r.getEvidenceRefs();
        if (data instanceof ShadowRunMetadataResult r && r.getEvidenceRefs() != null) return r.getEvidenceRefs();
        return List.of();
    }

    private <T> ResponseEntity<ApiResponse<McpToolEnvelope<T>>> badRequest(
            String tool, String traceId, Long tenantId, Long userId, Long caseId, UUID runId, String message, long startedAt) {
        return badRequest(tool, traceId, tenantId, userId, caseId, runId, message, startedAt, new String[0]);
    }

    private <T> ResponseEntity<ApiResponse<McpToolEnvelope<T>>> badRequest(
            String tool, String traceId, Long tenantId, Long userId, Long caseId, UUID runId,
            String message, long startedAt, String... missingFields) {
        long latency = System.currentTimeMillis() - startedAt;
        McpToolEnvelope<T> envelope = McpToolEnvelope.error(SCHEMA_VERSION, SOURCE_SYSTEM, traceId,
                "INPUT_PARTIAL", message, "INPUT_PARTIAL", List.of(), latency);
        envelope.setMissingFields(missingFields == null ? List.of() : Arrays.stream(missingFields)
                .filter(s -> s != null && !s.isBlank())
                .toList());
        log.info("MCP response summary: tool={} traceId={} tenantId={} userId={} caseId={} runId={} success={} decisionCode={} latency_ms={} http_status={}",
                tool, traceId, tenantId, userId, caseId, runId, envelope.getSuccess(), envelope.getDecisionCode(), latency, 400);
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
}
