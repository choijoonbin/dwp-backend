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

class AdminMailCompletionDeliverySupport extends AdminMailCompletionValidationSupport {

    AdminMailCompletionDeliverySupport(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability,
            AdminMailPurgeTransactions purgeTransactions,
            MailMemberDirectory memberDirectory) {
        super(repository, connectorRegistry, objectMapper, syncMaterializer,
                operationDurability, purgeTransactions, memberDirectory);
    }

    @Transactional(readOnly = true)
    public RetentionSnapshot retention(long tenantId) {
        return retention(tenantId, true);
    }

    @Transactional(readOnly = true)
    public RetentionSnapshot retention(long tenantId, boolean includeSensitiveEvidence) {
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        List<ResourceRetentionPolicy> policies = List.of(
                new ResourceRetentionPolicy(
                        "THREADS", policy.retentionDays(), policy.retentionDays(),
                        "mail_tenant_policies.retention_days", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "MESSAGES", policy.retentionDays(), policy.retentionDays(),
                        "mail_tenant_policies.retention_days", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "ATTACHMENTS", policy.retentionDays(), policy.retentionDays(),
                        "mail_compose_attachments via thread lifecycle", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "DRAFTS", policy.retentionDays(), policy.retentionDays(),
                        "mail_draft_options via thread lifecycle", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "RECIPIENT_SNAPSHOTS", policy.retentionDays(), null,
                        "mail_group_recipient_snapshots append-only evidence", "RETAINED"),
                new ResourceRetentionPolicy(
                        "COMMAND_RECEIPTS", policy.retentionDays(), null,
                        "mail_draft_command_receipts immutable evidence", "RETAINED"),
                new ResourceRetentionPolicy(
                        "AUDIT_EVENTS", policy.retentionDays(), null,
                        "mail_audit_events governance evidence", "RETAINED"));
        return new RetentionSnapshot(
                now(), policy.version(), policies,
                repository.legalHolds(tenantId).stream()
                        .map(row -> includeSensitiveEvidence
                                ? legalHold(row) : redactedLegalHold(row))
                        .toList(),
                includeSensitiveEvidence ? purgeJobs(tenantId) : List.of());
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        List<DeliveryAuditItem> items = repository.deliveries(
                tenantId, state, query, safeSize, safePage * safeSize).stream()
                .map(row -> deliveryItem(tenantId, row, true)).toList();
        return new DeliveryAuditPage(
                items, repository.deliveryCount(tenantId, state, query),
                safePage, safeSize, now());
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, UUID accountId,
            String provider, String command, LocalDate dateFrom, LocalDate dateTo,
            int page, int pageSize) {
        return deliveryAudit(tenantId, state, query, accountId, provider, command,
                dateFrom, dateTo, page, pageSize, true);
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, UUID accountId,
            String provider, String command, LocalDate dateFrom, LocalDate dateTo,
            int page, int pageSize, boolean revealSensitive) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            badRequest("DELIVERY_AUDIT_DATE_RANGE_INVALID");
        }
        String normalizedCommand = normalized(command).toUpperCase(Locale.ROOT);
        if (!normalizedCommand.isEmpty() && !"SEND".equals(normalizedCommand)) {
            badRequest("DELIVERY_AUDIT_COMMAND_UNSUPPORTED");
        }
        List<DeliveryAuditItem> items = repository.deliveries(
                tenantId, state, query, accountId, provider, normalizedCommand,
                dateFrom, dateTo, safeSize, safePage * safeSize).stream()
                .map(row -> deliveryItem(tenantId, row, revealSensitive)).toList();
        return new DeliveryAuditPage(
                items, repository.deliveryCount(
                        tenantId, state, query, accountId, provider, normalizedCommand,
                        dateFrom, dateTo),
                safePage, safeSize, now());
    }

    @Transactional(noRollbackFor = DeliveryRecoveryBlocked.class)
    public DeliveryAuditItem recoverDelivery(
            long tenantId, long actorId, UUID deliveryId, String actionKind,
            String correlationId, DeliveryRecoveryRequest request) {
        return recoverDelivery(
                tenantId, actorId, deliveryId, actionKind, correlationId, request, false);
    }

    @Transactional(noRollbackFor = DeliveryRecoveryBlocked.class)
    public DeliveryAuditItem recoverDelivery(
            long tenantId, long actorId, UUID deliveryId, String actionKind,
            String correlationId, DeliveryRecoveryRequest request,
            boolean revealSensitive) {
        requireIdentity(tenantId, actorId);
        String action = requiredEnum(actionKind, Set.of("RECONCILE", "RETRY", "CANCEL"));
        String fingerprint = fingerprints.digest(
                "DELIVERY_RECOVERY", actorId, deliveryId, action, request.version());
        var replay = repository.recoveryCommand(tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().fingerprint(), fingerprint);
            if (!replay.get().deliveryId().equals(deliveryId)
                    || !replay.get().action().equals(action)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            if ("BLOCKED".equals(replay.get().result())) {
                throw new DeliveryRecoveryBlocked(
                        "DELIVERY_RECOVERY_BLOCKED_BY_ACCESS");
            }
            return deliveryItem(tenantId, repository.delivery(tenantId, deliveryId)
                    .orElseThrow(this::notFound), revealSensitive);
        }
        AdminMailCompletionRepository.DeliveryRow before = repository
                .delivery(tenantId, deliveryId).orElseThrow(this::notFound);
        if (before.version() != request.version()) conflict("DELIVERY_VERSION_CONFLICT");
        if (!fresh(before.updatedAt(), now(), DELIVERY_EVIDENCE_FRESH)) {
            conflict("STALE_DELIVERY_EVIDENCE");
        }
        if (deliveryBlockedByAccess(before)) {
            blockDeliveryRecovery(
                    tenantId, actorId, deliveryId, action, correlationId, request,
                    fingerprint, before, before.errorCode());
        }
        if ("RETRY".equals(action)) {
            if (!retryEligible(before)) {
                conflict("RETRY_EVIDENCE_INSUFFICIENT");
            }
        } else if ("CANCEL".equals(action)) {
            if (!cancelEligible(before)) {
                conflict("CANCEL_EVIDENCE_INSUFFICIENT");
            }
        } else {
            if (!reconcileEligible(before)) {
                conflict("RECONCILIATION_EVIDENCE_INSUFFICIENT");
            }
        }
        String authorizationError = deliveryRecoveryAuthorizationError(tenantId, before);
        if (authorizationError != null) {
            blockDeliveryRecovery(
                    tenantId, actorId, deliveryId, action, correlationId, request,
                    fingerprint, before, authorizationError);
        }

        int changed = switch (action) {
            case "RETRY" -> repository.retryDelivery(
                    tenantId, deliveryId, request.version());
            case "CANCEL" -> repository.cancelDelivery(
                    tenantId, deliveryId, request.version());
            default -> repository.failExpiredSandboxLease(
                    tenantId, deliveryId, request.version());
        };
        if (changed != 1) conflict("DELIVERY_VERSION_CONFLICT");
        AdminMailCompletionRepository.DeliveryRow after = repository
                .delivery(tenantId, deliveryId).orElseThrow(this::conflict);
        repository.insertRecoveryEvent(
                tenantId, deliveryId, actorId, action, "SUCCEEDED",
                recoveryEvidence(before, after, null), request.idempotencyKey(),
                fingerprint, correlationId);
        repository.audit(
                tenantId, actorId, "mail.delivery." + action.toLowerCase(Locale.ROOT),
                "MAIL_DELIVERY", deliveryId.toString(), correlationId,
                Map.of("state", before.status(), "version", before.version()),
                Map.of("state", after.status(), "version", after.version(),
                        "result", "SUCCEEDED"));
        return deliveryItem(tenantId, after, revealSensitive);
    }

    void blockDeliveryRecovery(
            long tenantId, long actorId, UUID deliveryId, String action,
            String correlationId, DeliveryRecoveryRequest request, String fingerprint,
            AdminMailCompletionRepository.DeliveryRow before, String errorCode) {
        String normalizedError = nullToEmpty(errorCode).isBlank()
                ? "MAIL_SEND_AUTHORIZATION_REVOKED" : errorCode;
        long blockedVersion = before.version();
        if (!deliveryBlockedByAccess(before)) {
            if (repository.blockDeliveryAccess(
                    tenantId, deliveryId, before.version(), normalizedError) != 1) {
                conflict("DELIVERY_VERSION_CONFLICT");
            }
            blockedVersion++;
        }
        Map<String, Object> evidence = Map.of(
                "errorCode", normalizedError,
                "state", before.status(),
                "versionBefore", before.version(),
                "versionAfter", blockedVersion,
                "authorizationCheckedAt", now().toString());
        repository.insertRecoveryEvent(
                tenantId, deliveryId, actorId, action, "BLOCKED",
                evidence, request.idempotencyKey(), fingerprint, correlationId);
        repository.audit(
                tenantId, actorId,
                "mail.delivery." + action.toLowerCase(Locale.ROOT) + ".blocked",
                "MAIL_DELIVERY", deliveryId.toString(), correlationId,
                Map.of("state", before.status(), "version", before.version()), evidence);
        throw new DeliveryRecoveryBlocked(normalizedError);
    }

    @Transactional
    public DeliveryExport createDeliveryExport(
            long tenantId, long actorId, DeliveryExportRequest request,
            boolean revealSensitive) {
        requireIdentity(tenantId, actorId);
        String fingerprint = fingerprints.digest(
                "DELIVERY_EXPORT", actorId, request.filters(), request.purpose());
        var existing = repository.exportByCommand(tenantId, actorId, request.idempotencyKey());
        if (existing.isPresent()) {
            String existingFingerprint = existing.get().requestFingerprint() == null
                    ? fingerprints.digest("DELIVERY_EXPORT", actorId,
                            existing.get().filters(), existing.get().purpose())
                    : existing.get().requestFingerprint();
            requireFingerprint(existingFingerprint, fingerprint);
            requireExportKind(existing.get(), "DELIVERY_AUDIT");
            return deliveryExport(tenantId, existing.get());
        }
        OffsetDateTime snapshotCutoff = now();
        OffsetDateTime expiresAt = snapshotCutoff.plusHours(24);
        String watermark = "DWP MAIL AUDIT • tenant " + tenantId + " • user " + actorId
                + " • " + snapshotCutoff;
        UUID exportId = UUID.randomUUID();
        String state = exportText(request.filters(), "state");
        String query = exportText(request.filters(), "query");
        UUID accountId = exportUuid(request.filters(), "accountId");
        String provider = exportText(request.filters(), "provider");
        String command = exportText(request.filters(), "command").toUpperCase(Locale.ROOT);
        LocalDate dateFrom = exportDate(request.filters(), "dateFrom");
        LocalDate dateTo = exportDate(request.filters(), "dateTo");
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            badRequest("DELIVERY_AUDIT_DATE_RANGE_INVALID");
        }
        if (!command.isEmpty() && !"SEND".equals(command)) {
            badRequest("DELIVERY_AUDIT_COMMAND_UNSUPPORTED");
        }
        List<AdminMailCompletionRepository.DeliveryRow> candidates =
                request.filters().isEmpty() && revealSensitive
                ? repository.deliveries(
                        tenantId, state, query, DELIVERY_EXPORT_ITEM_LIMIT + 1, 0)
                : repository.deliveries(
                        tenantId, state, query, accountId, provider, command,
                        dateFrom, dateTo, DELIVERY_EXPORT_ITEM_LIMIT + 1, 0);
        boolean truncated = candidates.size() > DELIVERY_EXPORT_ITEM_LIMIT;
        List<DeliveryAuditItem> items = candidates.stream()
                .limit(DELIVERY_EXPORT_ITEM_LIMIT)
                .map(row -> deliveryItem(tenantId, row, revealSensitive))
                .toList();
        String payload = deliveryExportPayload(
                exportId, snapshotCutoff, expiresAt, watermark,
                request.purpose().trim(), request.filters(), items, truncated);
        String payloadSha256 = sha256(payload);
        UUID inserted = repository.insertExport(
                exportId, tenantId, actorId, request.filters(),
                request.purpose().trim(), watermark, request.idempotencyKey(),
                expiresAt, payload, payloadSha256, items.size(), truncated,
                snapshotCutoff, "DELIVERY_AUDIT", Map.of(), null, fingerprint)
                .orElse(null);
        if (inserted == null) {
            AdminMailCompletionRepository.ExportRow winner = repository.exportByCommand(
                    tenantId, actorId, request.idempotencyKey()).orElseThrow(this::conflict);
            String existingFingerprint = winner.requestFingerprint() == null
                    ? fingerprints.digest("DELIVERY_EXPORT", actorId,
                            winner.filters(), winner.purpose())
                    : winner.requestFingerprint();
            requireFingerprint(existingFingerprint, fingerprint);
            requireExportKind(winner, "DELIVERY_AUDIT");
            return deliveryExport(tenantId, winner);
        }
        return deliveryExport(tenantId, repository.export(
                tenantId, actorId, inserted).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public DeliveryExport deliveryExport(long tenantId, UUID exportId) {
        AdminMailCompletionRepository.ExportRow export = repository
                .export(tenantId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "DELIVERY_AUDIT");
        return deliveryExport(tenantId, export);
    }

    @Transactional
    public DeliveryExport approveDeliveryExport(
            long tenantId, long actorId, UUID exportId,
            EvidenceExportApprovalRequest request) {
        approveEvidenceExport(tenantId, actorId, exportId, "DELIVERY_AUDIT", request);
        return deliveryExport(tenantId, repository.export(tenantId, exportId)
                .orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public String deliveryExportJson(long tenantId, long actorId, UUID exportId) {
        requireIdentity(tenantId, actorId);
        AdminMailCompletionRepository.ExportRow export = repository
                .export(tenantId, actorId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "DELIVERY_AUDIT");
        return evidenceExportJson(export);
    }

    @Transactional
    public RetentionEvidenceExport createRetentionEvidenceExport(
            long tenantId, long actorId, RetentionEvidenceExportRequest request) {
        requireIdentity(tenantId, actorId);
        validateScope(request.scope(), "RETENTION_EXPORT_SCOPE_INVALID");
        if (!Boolean.TRUE.equals(request.scope().get("tenant"))
                || request.scope().containsKey("accountId")
                || request.scope().containsKey("accountIds")
                || request.scope().containsKey("threadId")
                || request.scope().containsKey("threadIds")) {
            badRequest("RETENTION_EXPORT_NARROW_SCOPE_UNSUPPORTED");
        }
        if (request.scope().containsKey("resourceTypes")
                && !Set.copyOf(stringSelectors(request.scope().get("resourceTypes")))
                        .equals(RETENTION_RESOURCE_TYPES)) {
            badRequest("RETENTION_EXPORT_PARTIAL_RESOURCE_SCOPE_UNSUPPORTED");
        }
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) {
            conflict("RETENTION_POLICY_VERSION_CONFLICT");
        }
        String fingerprint = fingerprints.digest(
                "RETENTION_EVIDENCE_EXPORT", actorId, request.scope(),
                request.purpose().trim(), request.policyVersion());
        var existing = repository.exportByCommand(tenantId, actorId, request.idempotencyKey());
        if (existing.isPresent()) {
            requireExportKind(existing.get(), "RETENTION_EVIDENCE");
            requireFingerprint(existing.get().requestFingerprint(), fingerprint);
            return retentionEvidenceExport(tenantId, existing.get());
        }
        OffsetDateTime cutoff = now();
        OffsetDateTime expiresAt = cutoff.plusHours(24);
        UUID exportId = UUID.randomUUID();
        String watermark = "DWP MAIL RETENTION EVIDENCE • tenant " + tenantId
                + " • user " + actorId + " • " + cutoff;
        RetentionSnapshot snapshot = retention(tenantId, true);
        Map<String, Object> payloadValue = new LinkedHashMap<>();
        payloadValue.put("exportId", exportId);
        payloadValue.put("generatedAt", cutoff);
        payloadValue.put("snapshotCutoff", cutoff);
        payloadValue.put("expiresAt", expiresAt);
        payloadValue.put("watermark", watermark);
        payloadValue.put("purpose", request.purpose().trim());
        payloadValue.put("scope", request.scope());
        payloadValue.put("policyVersion", request.policyVersion());
        payloadValue.put("retention", snapshot);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadValue);
        } catch (Exception failure) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "RETENTION_EXPORT_SERIALIZATION_FAILED");
        }
        String payloadSha256 = sha256(payload);
        UUID inserted = repository.insertExport(
                exportId, tenantId, actorId, Map.of(), request.purpose().trim(),
                watermark, request.idempotencyKey(), expiresAt, payload, payloadSha256,
                snapshot.resourcePolicies().size() + snapshot.holds().size()
                        + snapshot.purgeJobs().size(),
                false, cutoff, "RETENTION_EVIDENCE", request.scope(),
                request.policyVersion(), fingerprint).orElse(null);
        if (inserted == null) {
            AdminMailCompletionRepository.ExportRow winner = repository
                    .exportByCommand(tenantId, actorId, request.idempotencyKey())
                    .orElseThrow(this::conflict);
            requireExportKind(winner, "RETENTION_EVIDENCE");
            requireFingerprint(winner.requestFingerprint(), fingerprint);
            return retentionEvidenceExport(tenantId, winner);
        }
        return retentionEvidenceExport(tenantId, repository.export(tenantId, inserted)
                .orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public RetentionEvidenceExport retentionEvidenceExport(long tenantId, UUID exportId) {
        AdminMailCompletionRepository.ExportRow export = repository.export(tenantId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "RETENTION_EVIDENCE");
        return retentionEvidenceExport(tenantId, export);
    }

    @Transactional
    public RetentionEvidenceExport approveRetentionEvidenceExport(
            long tenantId, long actorId, UUID exportId,
            EvidenceExportApprovalRequest request) {
        approveEvidenceExport(tenantId, actorId, exportId, "RETENTION_EVIDENCE", request);
        return retentionEvidenceExport(tenantId, repository.export(tenantId, exportId)
                .orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public String retentionEvidenceExportJson(long tenantId, long actorId, UUID exportId) {
        requireIdentity(tenantId, actorId);
        AdminMailCompletionRepository.ExportRow export = repository.export(tenantId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "RETENTION_EVIDENCE");
        return evidenceExportJson(export);
    }

    String evidenceExportJson(AdminMailCompletionRepository.ExportRow export) {
        if (export.expiresAt().isBefore(now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "EVIDENCE_EXPORT_EXPIRED");
        }
        if (!"READY".equals(export.state())
                || export.snapshotPayload() == null
                || export.payloadSha256() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "EVIDENCE_EXPORT_APPROVAL_REQUIRED");
        }
        if (!MessageDigest.isEqual(
                export.payloadSha256().getBytes(StandardCharsets.US_ASCII),
                sha256(export.snapshotPayload()).getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "EVIDENCE_EXPORT_INTEGRITY_CHECK_FAILED");
        }
        return export.snapshotPayload();
    }

    String deliveryExportPayload(
            UUID exportId,
            OffsetDateTime snapshotCutoff,
            OffsetDateTime expiresAt,
            String watermark,
            String purpose,
            Map<String, Object> filters,
            List<DeliveryAuditItem> items,
            boolean truncated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportId", exportId);
        payload.put("generatedAt", snapshotCutoff);
        payload.put("snapshotCutoff", snapshotCutoff);
        payload.put("expiresAt", expiresAt);
        payload.put("watermark", watermark);
        payload.put("purpose", purpose);
        payload.put("filters", filters);
        payload.put("itemCount", items.size());
        payload.put("truncated", truncated);
        payload.put("itemLimit", DELIVERY_EXPORT_ITEM_LIMIT);
        payload.put("items", items);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception serializationFailure) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY_EXPORT_SERIALIZATION_FAILED");
        }
    }

    String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

}
