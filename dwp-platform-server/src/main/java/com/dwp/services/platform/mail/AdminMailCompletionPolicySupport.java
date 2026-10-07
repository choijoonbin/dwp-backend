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

class AdminMailCompletionPolicySupport extends AdminMailCompletionDeliverySupport {

    AdminMailCompletionPolicySupport(
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
    public PolicyGovernance policyGovernance(long tenantId) {
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        List<PolicyEvidenceRow> rows = List.of(
                policyRow("externalSenderBanner", policy.externalBanner(), "UNKNOWN",
                        "UNVERIFIED", "CLIENT_RENDERER_NOT_ATTESTED", policy.updatedAt()),
                policyRow("blockRemoteImages", policy.blockRemoteImages(), policy.blockRemoteImages(),
                        "ENFORCED", "MailWorkspaceRepository", policy.updatedAt()),
                policyRow("allowSharedInboxes", policy.allowSharedInboxes(), policy.allowSharedInboxes(),
                        "ENFORCED", "MailAccessSql", policy.updatedAt()),
                policyRow("aiAssistanceEnabled", policy.aiAssistance(), policy.aiAssistance(),
                        "ENFORCED", "MailService", policy.updatedAt()),
                policyRow("aiCrossAppActionsEnabled", policy.aiCrossAppActions(),
                        policy.aiCrossAppActions(), "ENFORCED", "MailService", policy.updatedAt()),
                policyRow("aiAutoExecuteEnabled", policy.aiAutoExecute(), false,
                        "ENFORCED", "DATABASE_CONSTRAINT", policy.updatedAt()),
                policyRow("retentionDays", policy.retentionDays(), policy.retentionDays(),
                        "ENFORCED", "AdminMailCompletionService", policy.updatedAt()),
                policyRow("maximumAttachmentMb", policy.maximumAttachmentMb(),
                        policy.maximumAttachmentMb(), "ENFORCED",
                        "MailWorkspaceService", policy.updatedAt()),
                policyUnavailableRow("attachmentAllowlist", "ATTACHMENT", "ALLOWLIST",
                        "ATTACHMENT_ALLOWLIST_NOT_CONFIGURED"),
                policyUnavailableRow("attachmentScannerReadiness", "ATTACHMENT", "SCANNER",
                        "SCANNER_RUNTIME_ATTESTATION_UNAVAILABLE"),
                policyUnavailableRow("dlpReadiness", "CONTENT", "DLP",
                        "DLP_RUNTIME_ATTESTATION_UNAVAILABLE"),
                policyUnavailableRow("aiTargetApplications", "AI", "TARGET_APPLICATIONS",
                        "AI_TARGET_SCOPE_NOT_CONFIGURED"),
                policyUnavailableRow("aiDataScopes", "AI", "DATA_SCOPE",
                        "AI_DATA_SCOPE_NOT_CONFIGURED"),
                policyUnavailableRow("aiExternalTransfer", "AI", "EXTERNAL_TRANSFER",
                        "AI_EXTERNAL_TRANSFER_EVIDENCE_UNAVAILABLE"),
                policyRow("aiReviewRequirement", !policy.aiAutoExecute(),
                        !policy.aiAutoExecute(), "ENFORCED", "MailService", policy.updatedAt()));
        List<PolicyHistory> history = repository.policyHistory(tenantId, 100).stream()
                .map(row -> new PolicyHistory(
                        row.id(), row.version(), "user:" + row.actorId(), row.changedAt(),
                        row.diff().toString(), row.result(), nullToEmpty(row.correlationId()),
                        "user:" + row.actorId(), null,
                        "APPLIED".equals(row.result()) ? row.changedAt() : null,
                        "APPLIED".equals(row.result()) ? null : row.result(),
                        "UNAVAILABLE", null))
                .toList();
        Map<String, List<PolicyEvidenceRow>> domains = rows.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        PolicyEvidenceRow::domain, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        List<PolicyRecoveryEvidence> recovery = history.stream()
                .filter(row -> row.failureCode() != null)
                .map(row -> new PolicyRecoveryEvidence(
                        row.historyId(), row.version(), row.recoveryState(),
                        row.failureCode(), row.recoveryRef(), row.changedAt()))
                .toList();
        return new PolicyGovernance(
                now(), policy.version(), rows, history, domains, List.of(), recovery);
    }

    @Transactional
    public LegalHold createLegalHold(
            long tenantId, long actorId, String correlationId, LegalHoldRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        validateLegalHold(request, false);
        String fingerprint = legalHoldFingerprint(actorId, null, "CREATE", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "LEGAL_HOLD_CREATE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) return replayHold(tenantId, replay);
        UUID id = repository.insertLegalHold(
                tenantId, request.name().trim(), request.safeCaseRef().trim(), request.scope(),
                request.startsAt(), request.expiresAt(), actorId);
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "LEGAL_HOLD", id);
        repository.audit(
                tenantId, actorId, "mail.legal.hold.created", "MAIL_LEGAL_HOLD",
                id.toString(), correlationId, Map.of(), Map.of("version", 0));
        return legalHold(repository.legalHold(tenantId, id).orElseThrow(this::conflict));
    }

    @Transactional
    public LegalHold updateLegalHold(
            long tenantId, long actorId, UUID holdId, String correlationId,
            LegalHoldRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        validateLegalHold(request, true);
        String fingerprint = legalHoldFingerprint(actorId, holdId, "UPDATE", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "LEGAL_HOLD_UPDATE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) return replayHold(tenantId, replay);
        AdminMailCompletionRepository.LegalHoldRow current = repository
                .legalHold(tenantId, holdId).orElseThrow(this::notFound);
        if (current.version() != request.version()) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        requireNonReducingActiveHoldUpdate(current, request);
        if (repository.updateLegalHold(
                tenantId, holdId, request.name().trim(), request.safeCaseRef().trim(),
                request.scope(), request.startsAt(), request.expiresAt(),
                request.version(), actorId) != 1) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "LEGAL_HOLD", holdId);
        repository.audit(
                tenantId, actorId, "mail.legal.hold.updated", "MAIL_LEGAL_HOLD",
                holdId.toString(), correlationId, Map.of("version", request.version()),
                Map.of("version", request.version() + 1));
        return legalHold(repository.legalHold(tenantId, holdId).orElseThrow(this::conflict));
    }

    @Transactional
    public LegalHoldReleasePreview previewLegalHoldRelease(
            long tenantId, long actorId, UUID holdId, String correlationId,
            LegalHoldReleasePreviewRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        String requestFingerprint = fingerprints.digest(
                "LEGAL_HOLD_RELEASE_PREVIEW", actorId, holdId,
                request.holdVersion(), request.policyVersion());
        var replay = repository.legalHoldReleasePreviewByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), requestFingerprint);
            if (!replay.get().holdId().equals(holdId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return legalHoldReleasePreview(tenantId, replay.get());
        }
        AdminMailCompletionRepository.LegalHoldRow hold = repository
                .legalHold(tenantId, holdId).orElseThrow(this::notFound);
        requireReleasableHold(hold, request.holdVersion());
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) {
            conflict("POLICY_VERSION_CONFLICT");
        }
        OffsetDateTime generatedAt = now();
        OffsetDateTime retentionBoundary = generatedAt.minusDays(policy.retentionDays());
        List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds = repository
                .activeLegalHolds(tenantId).stream()
                .filter(active -> !active.id().equals(hold.id())).toList();
        LegalHoldReleaseAssessment assessment = assessLegalHoldRelease(
                repository.purgeCandidates(tenantId, retentionBoundary), hold,
                otherActiveHolds);
        String snapshotFingerprint = legalHoldReleaseSnapshotFingerprint(
                tenantId, hold, policy.version(), retentionBoundary,
                otherActiveHolds, assessment);
        UUID previewId = repository.insertLegalHoldReleasePreview(
                tenantId, holdId, actorId, hold.version(), policy.version(), hold.scope(),
                retentionBoundary, snapshotFingerprint, requestFingerprint,
                assessment.affected(), assessment.currentlyHeld(), assessment.purgeSafe(),
                assessment.stillProtected(), assessment.providerRequired(),
                request.idempotencyKey(), generatedAt.plusMinutes(15));
        repository.audit(
                tenantId, actorId, "mail.legal.hold.release.previewed",
                "MAIL_LEGAL_HOLD_RELEASE_PREVIEW", previewId.toString(), correlationId,
                Map.of("holdId", holdId, "holdVersion", hold.version(),
                        "policyVersion", policy.version()),
                Map.of("fingerprint", snapshotFingerprint,
                        "affected", assessment.affected(),
                        "purgeSafeAfterRelease", assessment.purgeSafe(),
                        "expiresAt", generatedAt.plusMinutes(15)));
        return legalHoldReleasePreview(tenantId, repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public LegalHoldReleasePreview legalHoldReleasePreview(
            long tenantId, UUID previewId) {
        return legalHoldReleasePreview(tenantId, repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::notFound));
    }

    @Transactional
    public LegalHoldReleaseApproval approveLegalHoldRelease(
            long tenantId, long actorId, UUID previewId, String correlationId,
            LegalHoldReleaseApprovalRequest request) {
        requireIdentity(tenantId, actorId);
        String decision = requiredEnum(request.decision(), Set.of("APPROVE", "REJECT"));
        String requestFingerprint = fingerprints.digest(
                "LEGAL_HOLD_RELEASE_APPROVAL", actorId, previewId, decision,
                request.fingerprint().toLowerCase(Locale.ROOT), request.holdVersion(),
                request.policyVersion());
        repository.lockRetentionLifecycle(tenantId);
        var replay = repository.legalHoldReleaseApprovalByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), requestFingerprint);
            if (!replay.get().previewId().equals(previewId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return legalHoldReleaseApproval(replay.get());
        }
        AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview = repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::notFound);
        requireLiveLegalHoldReleasePreview(preview, request.fingerprint(),
                request.holdVersion(), request.policyVersion());
        if (preview.requesterId() == actorId) {
            conflict("LEGAL_HOLD_RELEASE_SELF_APPROVAL_FORBIDDEN");
        }
        if (repository.legalHoldReleaseExecutionByPreview(tenantId, previewId).isPresent()) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_ALREADY_EXECUTED");
        }
        if (repository.legalHoldReleaseApprovals(tenantId, previewId).stream()
                .anyMatch(approval -> "REJECT".equals(approval.decision()))) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_REJECTED");
        }
        requireCurrentLegalHoldReleaseVersions(tenantId, preview);
        UUID approvalId;
        try {
            approvalId = repository.insertLegalHoldReleaseApproval(
                    tenantId, previewId, actorId, decision, preview.holdVersion(),
                    preview.policyVersion(), preview.fingerprint(), requestFingerprint,
                    request.idempotencyKey());
        } catch (DataIntegrityViolationException duplicate) {
            conflict("LEGAL_HOLD_RELEASE_APPROVER_ALREADY_RECORDED");
            return null;
        }
        AdminMailCompletionRepository.LegalHoldReleaseApprovalRow approval =
                new AdminMailCompletionRepository.LegalHoldReleaseApprovalRow(
                        approvalId, previewId, actorId, decision, preview.holdVersion(),
                        preview.policyVersion(), preview.fingerprint(), requestFingerprint,
                        request.idempotencyKey(), now());
        repository.audit(
                tenantId, actorId, "mail.legal.hold.release." + decision.toLowerCase(Locale.ROOT),
                "MAIL_LEGAL_HOLD_RELEASE_PREVIEW", previewId.toString(), correlationId,
                Map.of("state", "AWAITING_APPROVAL"),
                Map.of("decision", decision, "approvalId", approvalId,
                        "holdVersion", preview.holdVersion(),
                        "policyVersion", preview.policyVersion()));
        return legalHoldReleaseApproval(approval);
    }

    @Transactional
    public LegalHoldReleaseExecution executeLegalHoldRelease(
            long tenantId, long actorId, UUID previewId, String correlationId,
            LegalHoldReleaseExecuteRequest request) {
        requireIdentity(tenantId, actorId);
        String normalizedFingerprint = request.fingerprint().toLowerCase(Locale.ROOT);
        String requestFingerprint = fingerprints.digest(
                "LEGAL_HOLD_RELEASE_EXECUTE", actorId, previewId, normalizedFingerprint,
                request.holdVersion(), request.policyVersion());
        repository.lockRetentionLifecycle(tenantId);
        var replay = repository.legalHoldReleaseExecutionByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), requestFingerprint);
            if (!replay.get().previewId().equals(previewId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return legalHoldReleaseExecution(tenantId, replay.get(), true);
        }
        if (repository.legalHoldReleaseExecutionByPreview(tenantId, previewId).isPresent()) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_ALREADY_EXECUTED");
        }
        AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview = repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::notFound);
        requireLiveLegalHoldReleasePreview(preview, normalizedFingerprint,
                request.holdVersion(), request.policyVersion());
        AdminMailCompletionRepository.LegalHoldRow hold =
                requireCurrentLegalHoldReleaseVersions(tenantId, preview);
        List<AdminMailCompletionRepository.LegalHoldReleaseApprovalRow> approvals =
                repository.legalHoldReleaseApprovals(tenantId, previewId);
        if (approvals.stream().anyMatch(approval -> "REJECT".equals(approval.decision()))) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_REJECTED");
        }
        AdminMailCompletionRepository.LegalHoldReleaseApprovalRow approval = approvals.stream()
                .filter(item -> "APPROVE".equals(item.decision()))
                .filter(item -> item.approverId() != preview.requesterId())
                .findFirst().orElseThrow(() ->
                        conflictException("LEGAL_HOLD_RELEASE_APPROVAL_REQUIRED"));
        List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds = repository
                .activeLegalHolds(tenantId).stream()
                .filter(active -> !active.id().equals(hold.id())).toList();
        LegalHoldReleaseAssessment currentAssessment = assessLegalHoldRelease(
                repository.purgeCandidates(tenantId, preview.retentionBoundary()),
                hold, otherActiveHolds);
        String currentFingerprint = legalHoldReleaseSnapshotFingerprint(
                tenantId, hold, preview.policyVersion(), preview.retentionBoundary(),
                otherActiveHolds, currentAssessment);
        if (!preview.fingerprint().equals(currentFingerprint)
                || !releaseAssessmentMatches(preview, currentAssessment)) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_STALE");
        }
        if (repository.releaseLegalHold(
                tenantId, hold.id(), hold.version(), actorId) != 1) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        UUID executionId = repository.insertLegalHoldReleaseExecution(
                tenantId, previewId, hold.id(), preview.requesterId(),
                approval.approverId(), actorId, hold.version(), preview.policyVersion(),
                preview.fingerprint(), requestFingerprint, request.idempotencyKey());
        repository.audit(
                tenantId, actorId, "mail.legal.hold.released", "MAIL_LEGAL_HOLD",
                hold.id().toString(), correlationId,
                Map.of("version", hold.version(), "status", hold.status(),
                        "releasePreviewId", previewId,
                        "fingerprint", preview.fingerprint()),
                Map.of("version", hold.version() + 1, "status", "RELEASED",
                        "executionId", executionId, "approvedBy", approval.approverId(),
                        "purgeStarted", false));
        AdminMailCompletionRepository.LegalHoldReleaseExecutionRow execution =
                new AdminMailCompletionRepository.LegalHoldReleaseExecutionRow(
                        executionId, previewId, hold.id(), preview.requesterId(),
                        approval.approverId(), actorId, hold.version(), hold.version() + 1,
                        preview.policyVersion(), preview.fingerprint(), requestFingerprint,
                        request.idempotencyKey(), now());
        return legalHoldReleaseExecution(tenantId, execution, false);
    }

    @Transactional
    public PurgePreview previewPurge(
            long tenantId, long actorId, PurgePreviewRequest request) {
        requireIdentity(tenantId, actorId);
        validatePurgeRequest(request);
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) conflict("POLICY_VERSION_CONFLICT");
        OffsetDateTime effectiveBefore = retentionBoundary(request.before(), policy.retentionDays());
        var existing = repository.purgePreviewByCommand(
                tenantId, actorId, request.idempotencyKey());
        String commandFingerprint = fingerprints.digest(
                "PURGE_PREVIEW", actorId, request.scope(), normalizedResources(request.resourceTypes()),
                effectiveBefore, request.policyVersion());
        if (existing.isPresent()) {
            String existingCommand = fingerprints.digest(
                    "PURGE_PREVIEW", actorId, existing.get().scope(),
                    normalizedResources(existing.get().resourceTypes()), existing.get().before(),
                    existing.get().policyVersion());
            requireFingerprint(existingCommand, commandFingerprint);
            return purgePreview(tenantId, existing.get());
        }
        AdminMailCompletionRepository.CandidateSet candidates =
                repository.purgeCandidates(tenantId, effectiveBefore);
        List<AdminMailCompletionRepository.LegalHoldRow> activeHolds =
                repository.activeLegalHolds(tenantId);
        PurgeAssessment assessment = assessPurge(
                candidates, request.scope(), request.resourceTypes(), activeHolds);
        List<String> partial = assessment.hasExternalProvider()
                ? List.of("EXTERNAL_PROVIDER_DELETE_UNAVAILABLE") : List.of();
        String snapshotFingerprint = purgeFingerprint(
                tenantId, request.scope(), request.resourceTypes(), effectiveBefore,
                policy.version(), activeHolds, assessment);
        UUID snapshotId = repository.insertPurgePreview(
                tenantId, actorId, request.scope(), normalizedResources(request.resourceTypes()),
                effectiveBefore, snapshotFingerprint, assessment.total(), assessment.held(),
                assessment.eligible(), partial,
                policy.version(), request.idempotencyKey(), now().plusMinutes(15));
        repository.insertPurgeCandidateRows(
                tenantId, snapshotId, assessment.snapshotRows());
        return purgePreview(tenantId, repository.purgePreview(tenantId, snapshotId)
                .orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public List<PurgePreview> activePurgePreviews(long tenantId) {
        return activePurgePreviews(tenantId, true);
    }

    @Transactional(readOnly = true)
    public List<PurgePreview> activePurgePreviews(
            long tenantId, boolean includeSensitiveScope) {
        return repository.activePurgePreviews(tenantId, 50).stream()
                .map(row -> purgePreview(tenantId, row))
                .map(preview -> purgePreviewProjection(preview, includeSensitiveScope))
                .toList();
    }

    @Transactional(readOnly = true)
    public PurgePreview purgePreview(long tenantId, UUID snapshotId) {
        return purgePreview(tenantId, snapshotId, true);
    }

    @Transactional(readOnly = true)
    public PurgePreview purgePreview(
            long tenantId, UUID snapshotId, boolean includeSensitiveScope) {
        return purgePreviewProjection(
                purgePreview(tenantId, repository.purgePreview(tenantId, snapshotId)
                        .orElseThrow(this::notFound)),
                includeSensitiveScope);
    }

    @Transactional
    public PurgeApproval approvePurge(
            long tenantId, long actorId, UUID snapshotId, PurgeApprovalRequest request) {
        requireIdentity(tenantId, actorId);
        if (!"APPROVE".equalsIgnoreCase(request.decision())) {
            badRequest("PURGE_APPROVAL_DECISION_INVALID");
        }
        AdminMailCompletionRepository.PurgePreviewRow preview = repository
                .purgePreview(tenantId, snapshotId).orElseThrow(this::notFound);
        requireLivePreview(preview, request.policyVersion());
        var replay = repository.purgeApprovalByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().snapshotId().equals(snapshotId)
                    || replay.get().policyVersion() != request.policyVersion()) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return purgeApproval(tenantId, replay.get());
        }
        UUID approvalId;
        try {
            approvalId = repository.insertPurgeApproval(
                    tenantId, snapshotId, actorId, request.policyVersion(),
                    request.idempotencyKey());
        } catch (DataIntegrityViolationException duplicateApprover) {
            conflict("PURGE_APPROVER_ALREADY_RECORDED");
            return null;
        }
        var approval = new AdminMailCompletionRepository.PurgeApprovalRow(
                approvalId, snapshotId, actorId, request.policyVersion(), now());
        repository.audit(
                tenantId, actorId, "mail.purge.approved", "MAIL_PURGE_PREVIEW",
                snapshotId.toString(), null, Map.of(),
                Map.of("approvalId", approvalId, "policyVersion", request.policyVersion()));
        return purgeApproval(tenantId, approval);
    }

    @Transactional
    public PurgeJob executePurge(
            long tenantId, long actorId, UUID snapshotId, String correlationId,
            PurgeExecuteRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        AdminMailCompletionRepository.PurgePreviewRow preview = repository
                .purgePreview(tenantId, snapshotId).orElseThrow(this::notFound);
        var replay = repository.purgeJobByCommand(tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().snapshotId().equals(snapshotId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            if (preview.policyVersion() != request.policyVersion()) {
                conflict("IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            requireFingerprint(
                    preview.fingerprint(), request.fingerprint().toLowerCase(Locale.ROOT));
            return purgeJob(replay.get());
        }
        if (repository.purgeJobBySnapshot(tenantId, snapshotId).isPresent()) {
            conflict("PURGE_SNAPSHOT_ALREADY_EXECUTED");
        }
        requireLivePreview(preview, request.policyVersion());
        requireFingerprint(preview.fingerprint(), request.fingerprint().toLowerCase(Locale.ROOT));
        if (repository.activeHoldCount(tenantId) > 0) {
            conflict("ACTIVE_LEGAL_HOLD_BLOCKS_PURGE");
        }
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) conflict("POLICY_VERSION_CONFLICT");
        if (!preview.partialSources().isEmpty()) conflict("PURGE_SOURCE_COVERAGE_INCOMPLETE");
        if (repository.distinctApprovals(tenantId, snapshotId, request.policyVersion()) < 2) {
            conflict("TWO_DISTINCT_APPROVERS_REQUIRED");
        }
        AdminMailCompletionRepository.CandidateSet candidates =
                repository.purgeCandidates(tenantId, preview.before());
        List<AdminMailCompletionRepository.LegalHoldRow> activeHolds =
                repository.activeLegalHolds(tenantId);
        PurgeAssessment assessment = assessPurge(
                candidates, preview.scope(), preview.resourceTypes(), activeHolds);
        String currentFingerprint = purgeFingerprint(
                tenantId, preview.scope(), preview.resourceTypes(), preview.before(),
                policy.version(), activeHolds, assessment);
        requireFingerprint(preview.fingerprint(), currentFingerprint);
        if (assessment.total() != preview.total()
                || assessment.eligible() != preview.eligible()
                || assessment.held() != preview.held()) {
            conflict("PURGE_CANDIDATE_SET_CHANGED");
        }
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows =
                repository.purgeCandidateRows(tenantId, snapshotId);
        if (!snapshotRows.equals(assessment.snapshotRows())) {
            conflict("PURGE_CANDIDATE_SNAPSHOT_MISMATCH");
        }
        List<UUID> approvedEligibleThreadIds = snapshotRows.stream()
                .filter(row -> !row.held()).map(
                        AdminMailCompletionRepository.PurgeCandidateSnapshotRow::threadId)
                .toList();
        List<Map<String, Object>> acceptanceSteps = List.of(
                Map.of("step", "REVALIDATE_POLICY", "state", "SUCCEEDED",
                        "policyVersion", policy.version()),
                Map.of("step", "REVALIDATE_LEGAL_HOLDS", "state", "SUCCEEDED",
                        "activeHoldCount", activeHolds.size()),
                Map.of("step", "PRESERVE_IMMUTABLE_EVIDENCE", "state", "SUCCEEDED",
                        "blockedThreads", assessment.blockedThreadCount(),
                        "blockedMessages", assessment.blockedMessageCount()));
        UUID jobId = repository.insertPurgeJob(
                tenantId, snapshotId, actorId, request.idempotencyKey(),
                approvedEligibleThreadIds, acceptanceSteps);
        repository.audit(
                tenantId, actorId, "mail.purge.accepted", "MAIL_PURGE_JOB",
                jobId.toString(), correlationId,
                Map.of("candidateSnapshotId", snapshotId, "fingerprint", preview.fingerprint()),
                Map.of("result", "ACCEPTED",
                        "candidateThreads", approvedEligibleThreadIds.size(),
                        "preservedThreads", assessment.blockedThreadCount()));
        return purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public PurgeJob purgeJob(long tenantId, UUID jobId) {
        return purgeJob(tenantId, jobId, true);
    }

    @Transactional(readOnly = true)
    public PurgeJob purgeJob(long tenantId, UUID jobId, boolean includeSensitiveEvidence) {
        return purgeJobProjection(
                purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::notFound)),
                includeSensitiveEvidence);
    }

}
