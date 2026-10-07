package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

class AdminMailCompletionServiceBase {

    static final Duration SOURCE_FRESH = Duration.ofMinutes(5);
    static final Duration DELIVERY_EVIDENCE_FRESH = Duration.ofMinutes(2);
    static final int MAX_SYNC_PAGES_PER_ACCOUNT = 20;
    static final int DELIVERY_EXPORT_ITEM_LIMIT = 10_000;
    static final Set<String> PURGE_RESOURCE_TYPES = Set.of(
            "THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS");
    static final Set<String> RETENTION_RESOURCE_TYPES = Set.of(
            "THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS",
            "RECIPIENT_SNAPSHOTS", "COMMAND_RECEIPTS", "AUDIT_EVENTS");
    static final Set<String> DELIVERY_ACCESS_TERMINAL_ERRORS = Set.of(
            "MAIL_SEND_AUTHORIZATION_REVOKED",
            "MAIL_ADAPTER_NOT_DEPLOYED",
            "MAIL_ADAPTER_SEND_NOT_SUPPORTED",
            "MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED",
            "MAIL_ADAPTER_BCC_NOT_SUPPORTED",
            "MAIL_ADAPTER_HTML_NOT_SUPPORTED",
            "MAIL_ADAPTER_ATTACHMENTS_NOT_SUPPORTED",
            "MAIL_ADAPTER_CONFIGURATION_REQUIRED",
            "MAIL_ADAPTER_AUTHENTICATION_REQUIRED");
    static final Set<String> SCOPE_KEYS = Set.of(
            "tenant", "accountId", "accountIds", "threadId", "threadIds", "resourceTypes");

    final AdminMailCompletionRepository repository;
    final MailConnectorRegistry connectorRegistry;
    final AdminMailCommandFingerprint fingerprints;
    final ObjectMapper objectMapper;
    final MailExternalSyncMaterializer syncMaterializer;
    final AdminMailOperationDurability operationDurability;
    final AdminMailPurgeTransactions purgeTransactions;
    final MailMemberDirectory memberDirectory;

    AdminMailCompletionServiceBase(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability,
            AdminMailPurgeTransactions purgeTransactions,
            MailMemberDirectory memberDirectory) {
        this.repository = repository;
        this.connectorRegistry = connectorRegistry;
        this.fingerprints = new AdminMailCommandFingerprint(objectMapper);
        this.objectMapper = objectMapper;
        this.syncMaterializer = syncMaterializer;
        this.operationDurability = operationDurability;
        this.purgeTransactions = purgeTransactions;
        this.memberDirectory = memberDirectory;
    }

    void approveEvidenceExport(
            long tenantId, long actorId, UUID exportId, String expectedKind,
            EvidenceExportApprovalRequest request) {
        requireIdentity(tenantId, actorId);
        if (!"APPROVE".equalsIgnoreCase(request.decision().trim())) {
            badRequest("EVIDENCE_EXPORT_DECISION_UNSUPPORTED");
        }
        AdminMailCompletionRepository.ExportRow export = repository
                .exportForUpdate(tenantId, exportId).orElseThrow(this::notFound);
        requireExportKind(export, expectedKind);
        if (export.expiresAt().isBefore(now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "EVIDENCE_EXPORT_EXPIRED");
        }
        if (export.actorId() == actorId) {
            conflict("EVIDENCE_EXPORT_SELF_APPROVAL_FORBIDDEN");
        }
        String fingerprint = fingerprints.digest(
                "EVIDENCE_EXPORT_APPROVAL", actorId, exportId, expectedKind, "APPROVED");
        var replay = repository.exportApprovalByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), fingerprint);
            if (!replay.get().exportId().equals(exportId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            repository.markExportReady(tenantId, exportId);
            return;
        }
        UUID approvalId = repository.insertExportApproval(
                tenantId, exportId, actorId, request.idempotencyKey(), fingerprint)
                .orElse(null);
        if (approvalId == null) {
            conflict("EVIDENCE_EXPORT_APPROVAL_ALREADY_RECORDED");
        }
        repository.markExportReady(tenantId, exportId);
        repository.audit(
                tenantId, actorId, "mail.evidence-export.approved",
                "MAIL_EVIDENCE_EXPORT", exportId.toString(), null,
                Map.of("state", export.state()), Map.of(
                        "state", "READY", "approvalId", approvalId,
                        "exportKind", expectedKind));
    }

    DeliveryExport deliveryExport(
            long tenantId, AdminMailCompletionRepository.ExportRow row) {
        List<EvidenceExportApproval> approvals = evidenceExportApprovals(tenantId, row.id());
        boolean ready = "READY".equals(row.state()) && row.expiresAt().isAfter(now());
        return new DeliveryExport(
                row.id(), row.state(), row.filters(), row.expiresAt(), row.watermark(),
                row.itemCount(), row.truncated(), row.payloadSha256(), row.snapshotCutoff(),
                ready ? "/api/platform/v1/admin/mail/delivery-audit/exports/"
                        + row.id() + "/download" : null,
                ready ? "APPROVED" : "PENDING_APPROVAL", row.requiredApprovals(),
                approvals.size(), approvals);
    }

    RetentionEvidenceExport retentionEvidenceExport(
            long tenantId, AdminMailCompletionRepository.ExportRow row) {
        List<EvidenceExportApproval> approvals = evidenceExportApprovals(tenantId, row.id());
        boolean ready = "READY".equals(row.state()) && row.expiresAt().isAfter(now());
        return new RetentionEvidenceExport(
                row.id(), row.state(), row.exportScope(),
                row.policyVersion() == null ? 0 : row.policyVersion(),
                row.expiresAt(), row.watermark(), row.payloadSha256(), row.snapshotCutoff(),
                ready ? "/api/platform/v1/admin/mail/retention/evidence-exports/"
                        + row.id() + "/download" : null,
                ready ? "APPROVED" : "PENDING_APPROVAL", row.requiredApprovals(),
                approvals.size(), approvals);
    }

    List<EvidenceExportApproval> evidenceExportApprovals(
            long tenantId, UUID exportId) {
        return repository.exportApprovals(tenantId, exportId).stream()
                .map(row -> new EvidenceExportApproval(
                        row.id(), row.approverUserId(), row.decision(), row.decidedAt()))
                .toList();
    }

    void requireExportKind(
            AdminMailCompletionRepository.ExportRow export, String expectedKind) {
        if (!expectedKind.equals(export.exportKind())) {
            throw notFound();
        }
    }

    String exportText(Map<String, Object> filters, String key) {
        Object value = filters.get(key);
        if (value == null) return "";
        if (!(value instanceof String)) badRequest("DELIVERY_EXPORT_FILTER_INVALID");
        return ((String) value).trim();
    }

    UUID exportUuid(Map<String, Object> filters, String key) {
        String value = exportText(filters, key);
        if (value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            badRequest("DELIVERY_EXPORT_FILTER_INVALID");
            return null;
        }
    }

    LocalDate exportDate(Map<String, Object> filters, String key) {
        String value = exportText(filters, key);
        if (value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            badRequest("DELIVERY_EXPORT_FILTER_INVALID");
            return null;
        }
    }

    ConnectionOperation connectionOperation(
            AdminMailCompletionRepository.ConnectionOperationRow row, boolean replayed) {
        return new ConnectionOperation(
                row.id(), row.connectionId(), "SYNC".equals(row.kind())
                        ? "SYNCHRONIZE" : row.kind(), row.state(), row.acceptedAt(),
                row.completedAt(), nullToEmpty(row.correlationId()), row.evidenceAt(),
                row.errorCode(), replayed);
    }

    Map<String, Object> resultEvidence(String state, String error, UUID operationId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("operationId", operationId);
        evidence.put("result", state);
        if (error != null) evidence.put("errorCode", error);
        return evidence;
    }

    String sourceState(OffsetDateTime evidenceAt, OffsetDateTime current) {
        if (evidenceAt == null) return "UNAVAILABLE";
        return fresh(evidenceAt, current, SOURCE_FRESH) ? "CURRENT" : "STALE";
    }

    boolean fresh(OffsetDateTime evidenceAt, OffsetDateTime current, Duration maximumAge) {
        return evidenceAt != null && !evidenceAt.isAfter(current.plusSeconds(5))
                && !evidenceAt.isBefore(current.minus(maximumAge));
    }

    List<String> normalizedResources(List<String> resources) {
        return resources.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct().sorted().toList();
    }

    String requiredEnum(String value, Set<String> allowed) {
        String normalized = normalized(value).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) badRequest("UNSUPPORTED_OPERATION");
        return normalized;
    }

    void requireIdentity(long tenantId, long actorId) {
        if (tenantId <= 0 || actorId <= 0) badRequest("IDENTITY_CONTEXT_REQUIRED");
    }

    void requireFingerprint(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            conflict("IDEMPOTENCY_FINGERPRINT_MISMATCH");
        }
    }

    String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    String normalizedEmail(String value) {
        return normalized(value).toLowerCase(Locale.ROOT);
    }

    String trimToNull(String value) {
        String normalized = normalized(value);
        return normalized.isBlank() ? null : normalized;
    }

    String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    String safeResourceReference(String kind, String resourceReference) {
        String label = normalized(kind).toLowerCase(Locale.ROOT).replace('_', '-');
        if (resourceReference == null || resourceReference.isBlank()) return label;
        String value = resourceReference.trim();
        int suffixLength = Math.min(6, value.length());
        return label + ":••" + value.substring(value.length() - suffixLength);
    }

    String correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "MAIL_ADMIN_RESOURCE_NOT_FOUND");
    }

    ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MAIL_ADMIN_CONFLICT");
    }

    ResponseStatusException conflictException(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    ResponseStatusException badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    AdminOperationFailure failure(String code) {
        return new AdminOperationFailure(code, false);
    }

    static final class AdminOperationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        final boolean unknown;

        AdminOperationFailure(String code, boolean unknown) {
            super(code);
            this.code = code;
            this.unknown = unknown;
        }
    }

    static final class DeliveryRecoveryBlocked extends ResponseStatusException {
        private static final long serialVersionUID = 1L;

        DeliveryRecoveryBlocked(String code) {
            super(HttpStatus.CONFLICT, code);
        }
    }
}
