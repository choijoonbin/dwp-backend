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

class AdminMailCompletionValidationSupport extends AdminMailCompletionProjectionSupport {

    AdminMailCompletionValidationSupport(
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

    void validateConnectionRequest(String kind, ConnectionOperationRequest request) {
        if ("TEST_SEND".equals(kind)) {
            if (!Boolean.TRUE.equals(request.confirmedExternalImpact())) {
                badRequest("TEST_SEND_REQUIRES_EXTERNAL_IMPACT_CONFIRMATION");
            }
            if (request.recipient() == null || request.recipient().isBlank()) {
                badRequest("TEST_SEND_RECIPIENT_REQUIRED");
            }
        }
    }

    void validatePermissions(AccessPermissions permissions) {
        if (!(permissions.read() || permissions.sendAs() || permissions.sendOnBehalf()
                || permissions.assign() || permissions.manage())) {
            badRequest("AT_LEAST_ONE_PERMISSION_REQUIRED");
        }
        if (permissions.manage() && (!permissions.read() || !permissions.assign())) {
            badRequest("MANAGE_REQUIRES_READ_AND_ASSIGN");
        }
    }

    void validateLegalHold(LegalHoldRequest request, boolean versionRequired) {
        if (request.scope().isEmpty()) badRequest("LEGAL_HOLD_SCOPE_REQUIRED");
        validateScope(request.scope(), "LEGAL_HOLD_SCOPE_UNSUPPORTED");
        if (request.expiresAt() != null && !request.expiresAt().isAfter(request.startsAt())) {
            badRequest("LEGAL_HOLD_WINDOW_INVALID");
        }
        if (versionRequired && request.version() == null) {
            badRequest("LEGAL_HOLD_VERSION_REQUIRED");
        }
    }

    /**
     * An active hold may be widened in place, but its protected population or time window may
     * only be reduced through the separately approved release workflow. The tenant policy row is
     * locked before this comparison, so every hold mutation and release observes one serialized
     * retention lifecycle.
     */
    void requireNonReducingActiveHoldUpdate(
            AdminMailCompletionRepository.LegalHoldRow current,
            LegalHoldRequest request) {
        if (!"ACTIVE".equals(current.status())) return;
        if (request.startsAt().isAfter(current.startsAt())) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
        if (current.expiresAt() == null && request.expiresAt() != null) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
        if (current.expiresAt() != null && request.expiresAt() != null
                && request.expiresAt().isBefore(current.expiresAt())) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
        if (!scopeContains(current.scope(), request.scope())) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
    }

    boolean scopeContains(
            Map<String, Object> current, Map<String, Object> requested) {
        boolean currentTenant = Boolean.TRUE.equals(current.get("tenant"));
        boolean requestedTenant = Boolean.TRUE.equals(requested.get("tenant"));
        if (currentTenant && !requestedTenant) return false;

        Set<UUID> currentAccounts = uuidSelectors(current, "accountId", "accountIds");
        Set<UUID> requestedAccounts = uuidSelectors(requested, "accountId", "accountIds");
        Set<UUID> currentThreads = uuidSelectors(current, "threadId", "threadIds");
        Set<UUID> requestedThreads = uuidSelectors(requested, "threadId", "threadIds");
        if (!requestedTenant) {
            if (currentAccounts.isEmpty() != requestedAccounts.isEmpty()
                    || currentThreads.isEmpty() != requestedThreads.isEmpty()) {
                return false;
            }
            if (!requestedAccounts.containsAll(currentAccounts)
                    || !requestedThreads.containsAll(currentThreads)) {
                return false;
            }
        }

        boolean currentAllResources = !current.containsKey("resourceTypes");
        boolean requestedAllResources = !requested.containsKey("resourceTypes");
        if (currentAllResources && !requestedAllResources) return false;
        if (requestedAllResources) return true;
        Set<String> currentResources = Set.copyOf(stringSelectors(current.get("resourceTypes")));
        Set<String> requestedResources = Set.copyOf(
                stringSelectors(requested.get("resourceTypes")));
        return requestedResources.containsAll(currentResources);
    }

    void validatePurgeRequest(PurgePreviewRequest request) {
        validateScope(request.scope(), "PURGE_SCOPE_UNSUPPORTED");
        Set<String> normalized = Set.copyOf(normalizedResources(request.resourceTypes()));
        if (normalized.isEmpty() || !PURGE_RESOURCE_TYPES.containsAll(normalized)) {
            badRequest("PURGE_RESOURCE_TYPE_UNSUPPORTED");
        }
        if (request.before().isAfter(now())) badRequest("PURGE_BEFORE_MUST_NOT_BE_FUTURE");
    }

    void validateScope(Map<String, Object> scope, String errorCode) {
        if (scope == null || scope.isEmpty() || !SCOPE_KEYS.containsAll(scope.keySet())) {
            badRequest(errorCode);
        }
        boolean tenant = Boolean.TRUE.equals(scope.get("tenant"));
        boolean hasAccount = scope.containsKey("accountId") || scope.containsKey("accountIds");
        boolean hasThread = scope.containsKey("threadId") || scope.containsKey("threadIds");
        if (!tenant && !hasAccount && !hasThread) badRequest(errorCode);
        if (tenant && (hasAccount || hasThread)) badRequest(errorCode);
        try {
            uuidSelectors(scope, "accountId", "accountIds");
            uuidSelectors(scope, "threadId", "threadIds");
            if (scope.containsKey("resourceTypes")) {
                Set<String> resources = Set.copyOf(stringSelectors(scope.get("resourceTypes")));
                if (resources.isEmpty() || !RETENTION_RESOURCE_TYPES.containsAll(resources)) {
                    badRequest(errorCode);
                }
            }
        } catch (IllegalArgumentException invalidScope) {
            badRequest(errorCode);
        }
    }

    AdminMailCompletionRepository.AdminReceiptRow claimOrReplay(
            long tenantId, long actorId, String kind, UUID key,
            String fingerprint, String correlationId) {
        var existing = repository.adminReceipt(tenantId, actorId, key);
        if (existing.isPresent()) {
            requireAdminReceipt(existing.get(), kind, fingerprint);
            return existing.get();
        }
        if (repository.claimAdminReceipt(
                tenantId, actorId, kind, key, fingerprint, correlationId)) {
            return null;
        }
        AdminMailCompletionRepository.AdminReceiptRow winner = repository
                .adminReceipt(tenantId, actorId, key).orElseThrow(this::conflict);
        requireAdminReceipt(winner, kind, fingerprint);
        return winner;
    }

    void requireAdminReceipt(
            AdminMailCompletionRepository.AdminReceiptRow receipt,
            String kind, String fingerprint) {
        if (!receipt.commandKind().equals(kind)) conflict("IDEMPOTENCY_COMMAND_MISMATCH");
        requireFingerprint(receipt.fingerprint(), fingerprint);
        if (receipt.completedAt() == null || receipt.aggregateId() == null) {
            conflict("IDEMPOTENT_COMMAND_IN_PROGRESS");
        }
    }

    SharedInboxAccessMember replayMember(
            long tenantId, UUID inboxId,
            AdminMailCompletionRepository.AdminReceiptRow receipt,
            String expectedType) {
        if (!expectedType.equals(receipt.aggregateType())) conflict("IDEMPOTENCY_RESPONSE_MISMATCH");
        return accessMember(repository.accessGrant(tenantId, inboxId, receipt.aggregateId())
                .orElseThrow(this::conflict));
    }

    LegalHold replayHold(
            long tenantId, AdminMailCompletionRepository.AdminReceiptRow receipt) {
        if (!"LEGAL_HOLD".equals(receipt.aggregateType())) {
            conflict("IDEMPOTENCY_RESPONSE_MISMATCH");
        }
        return legalHold(repository.legalHold(tenantId, receipt.aggregateId())
                .orElseThrow(this::conflict));
    }

    String memberFingerprint(
            long actorId, UUID inboxId, UUID memberId, String kind,
            SharedInboxMemberRequest request) {
        return fingerprints.digest(
                "SHARED_MEMBER", kind, actorId, inboxId, memberId, request.userId(),
                request.permissions(), request.expiresAt(), request.impactAcknowledged(),
                request.version());
    }

    String memberFingerprint(
            long actorId, UUID inboxId, UUID memberId, String kind,
            SharedInboxMemberRevokeRequest request) {
        return fingerprints.digest(
                "SHARED_MEMBER", kind, actorId, inboxId, memberId,
                request.previewId(), normalized(request.fingerprint()),
                request.impactAcknowledged(), request.version());
    }

    String memberRevokePreviewFingerprint(
            long actorId, UUID inboxId, UUID memberId, long memberVersion,
            AdminMailCompletionRepository.ImpactRow impact,
            boolean providerRevocationRequired) {
        return fingerprints.digest(
                "SHARED_MEMBER_REVOKE_PREVIEW", actorId, inboxId, memberId, memberVersion,
                impact.activeAssignments(), impact.openDrafts(), impact.pendingCommands(),
                providerRevocationRequired);
    }

    String legalHoldFingerprint(
            long actorId, UUID holdId, String kind, LegalHoldRequest request) {
        return fingerprints.digest(
                "LEGAL_HOLD", kind, actorId, holdId, normalized(request.name()),
                normalized(request.safeCaseRef()), request.scope(), request.startsAt(),
                request.expiresAt(), request.version());
    }

    SharedInboxAccessMember accessMember(
            AdminMailCompletionRepository.AccessGrantRow row) {
        return new SharedInboxAccessMember(
                row.id(), row.userId(), row.displayName(), row.department(), row.state(),
                row.expiresAt(), new AccessPermissions(
                        row.read(), row.sendAs(), row.sendOnBehalf(), row.assign(), row.manage()),
                row.providerState(), row.version());
    }

    AccessImpact impact(
            AdminMailCompletionRepository.ImpactRow impact, boolean providerRevocationRequired) {
        return new AccessImpact(
                impact.activeAssignments(), impact.openDrafts(), impact.pendingCommands(),
                providerRevocationRequired);
    }

    String providerState(List<SharedInboxAccessMember> members) {
        if (members.stream().anyMatch(member -> "UNAVAILABLE".equals(member.providerState()))) {
            return "UNAVAILABLE";
        }
        if (members.stream().anyMatch(member -> "PARTIAL".equals(member.providerState()))) {
            return "PARTIAL";
        }
        if (members.stream().anyMatch(member -> "PENDING".equals(member.providerState()))) {
            return "PENDING";
        }
        return "APPLIED";
    }

    String providerMutationState(String providerType) {
        return "DWP_SANDBOX".equals(providerType) ? "APPLIED" : "UNAVAILABLE";
    }

    MailMemberDirectory.MemberIdentity requireActiveMember(
            long tenantId, long userId) {
        if (memberDirectory == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_DIRECTORY_UNAVAILABLE");
        }
        MailMemberDirectory.MemberIdentity identity =
                memberDirectory.requireActive(tenantId, userId);
        if (identity == null || !identity.activeTenantUser(tenantId, userId)) {
            badRequest("SHARED_MEMBER_USER_NOT_ACTIVE");
        }
        return identity;
    }

    PolicyEvidenceRow policyRow(
            String key, Object configured, Object effective, String state,
            String source, OffsetDateTime evidenceAt) {
        String error = "UNVERIFIED".equals(state) ? String.valueOf(source) : null;
        String evidenceSource = "UNVERIFIED".equals(state) ? "NO_RUNTIME_ATTESTATION" : source;
        String domain = policyDomain(key);
        String reviewRequirement = "aiReviewRequirement".equals(key)
                ? (Boolean.TRUE.equals(effective) ? "REQUIRED" : "NOT_REQUIRED")
                : "UNAVAILABLE";
        return new PolicyEvidenceRow(
                key, String.valueOf(configured), String.valueOf(effective), state,
                "TENANT", evidenceSource, evidenceAt, error,
                domain, key, "TENANT_DIRECT", false, null, null, "UNAVAILABLE",
                List.of(), List.of(), "UNAVAILABLE", reviewRequirement);
    }

    PolicyEvidenceRow policyUnavailableRow(
            String key, String domain, String contentKind, String errorCode) {
        return new PolicyEvidenceRow(
                key, "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "TENANT",
                "NO_RUNTIME_ATTESTATION", now(), errorCode,
                domain, contentKind, "TENANT_DIRECT", false, null,
                null, "UNAVAILABLE", List.of(), List.of(),
                "UNAVAILABLE", "UNAVAILABLE");
    }

    String policyDomain(String key) {
        if (key.startsWith("ai")) return "AI";
        if (key.toLowerCase(Locale.ROOT).contains("attachment")) return "ATTACHMENT";
        if (key.toLowerCase(Locale.ROOT).contains("retention")) return "RETENTION";
        return "CONTENT";
    }

    LegalHold legalHold(AdminMailCompletionRepository.LegalHoldRow row) {
        String status = "ACTIVE".equals(row.status())
                && row.expiresAt() != null && !row.expiresAt().isAfter(now())
                ? "EXPIRED" : row.status();
        return new LegalHold(
                row.id(), row.name(), row.caseRef(), row.scope(), status,
                row.startsAt(), row.expiresAt(), row.version());
    }

    LegalHold redactedLegalHold(AdminMailCompletionRepository.LegalHoldRow row) {
        LegalHold hold = legalHold(row);
        return new LegalHold(
                hold.holdId(), hold.name(), "REDACTED", Map.of(), hold.status(),
                hold.startsAt(), hold.expiresAt(), hold.version());
    }

    LegalHoldReleasePreview legalHoldReleasePreview(
            long tenantId, AdminMailCompletionRepository.LegalHoldReleasePreviewRow row) {
        List<LegalHoldReleaseApproval> approvals = repository
                .legalHoldReleaseApprovals(tenantId, row.id()).stream()
                .map(this::legalHoldReleaseApproval).toList();
        boolean released = repository
                .legalHoldReleaseExecutionByPreview(tenantId, row.id()).isPresent();
        String state;
        if (released) {
            state = "RELEASED";
        } else if (approvals.stream().anyMatch(item -> "REJECT".equals(item.decision()))) {
            state = "REJECTED";
        } else if (!row.expiresAt().isAfter(now())) {
            state = "EXPIRED";
        } else if (approvals.stream().anyMatch(item -> "APPROVE".equals(item.decision()))) {
            state = "APPROVED";
        } else {
            state = "AWAITING_APPROVAL";
        }
        int approverCount = (int) approvals.stream()
                .filter(item -> "APPROVE".equals(item.decision()))
                .map(LegalHoldReleaseApproval::approverUserId).distinct().count();
        return new LegalHoldReleasePreview(
                row.id(), row.holdId(), row.requesterId(), row.holdVersion(),
                row.policyVersion(), row.holdScope(), row.retentionBoundary(),
                row.fingerprint(), new LegalHoldReleaseImpact(
                        releaseCounts(row.affectedCounts()),
                        releaseCounts(row.currentlyHeldCounts()),
                        releaseCounts(row.purgeSafeCounts()),
                        releaseCounts(row.protectedCounts()),
                        releaseCounts(row.providerRequiredCounts())),
                state, approverCount, approvals, row.generatedAt(), row.expiresAt());
    }

    LegalHoldReleaseApproval legalHoldReleaseApproval(
            AdminMailCompletionRepository.LegalHoldReleaseApprovalRow row) {
        return new LegalHoldReleaseApproval(
                row.id(), row.previewId(), row.approverId(), row.decision(),
                row.holdVersion(), row.policyVersion(), row.decidedAt());
    }

    LegalHoldReleaseExecution legalHoldReleaseExecution(
            long tenantId, AdminMailCompletionRepository.LegalHoldReleaseExecutionRow row,
            boolean replayed) {
        LegalHold hold = legalHold(repository.legalHold(tenantId, row.holdId())
                .orElseThrow(this::conflict));
        return new LegalHoldReleaseExecution(
                row.id(), row.previewId(), row.holdId(), row.requesterId(),
                row.approvedById(), row.executorId(), row.policyVersion(),
                row.previewFingerprint(), hold, row.executedAt(), replayed);
    }

    void requireLiveLegalHoldReleasePreview(
            AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview,
            String fingerprint, long holdVersion, long policyVersion) {
        if (!preview.expiresAt().isAfter(now())) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_EXPIRED");
        }
        requireFingerprint(preview.fingerprint(), fingerprint.toLowerCase(Locale.ROOT));
        if (preview.holdVersion() != holdVersion) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        if (preview.policyVersion() != policyVersion) {
            conflict("POLICY_VERSION_CONFLICT");
        }
    }

    AdminMailCompletionRepository.LegalHoldRow requireCurrentLegalHoldReleaseVersions(
            long tenantId, AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview) {
        AdminMailCompletionRepository.LegalHoldRow hold = repository
                .legalHold(tenantId, preview.holdId()).orElseThrow(this::notFound);
        requireReleasableHold(hold, preview.holdVersion());
        if (!fingerprints.digest(hold.scope()).equals(
                fingerprints.digest(preview.holdScope()))) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_STALE");
        }
        if (repository.policy(tenantId).version() != preview.policyVersion()) {
            conflict("POLICY_VERSION_CONFLICT");
        }
        return hold;
    }

    void requireReleasableHold(
            AdminMailCompletionRepository.LegalHoldRow hold, long expectedVersion) {
        if (!"ACTIVE".equals(hold.status())) {
            conflict("LEGAL_HOLD_NOT_ACTIVE");
        }
        if (hold.version() != expectedVersion) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
    }

    LegalHoldReleaseAssessment assessLegalHoldRelease(
            AdminMailCompletionRepository.CandidateSet candidates,
            AdminMailCompletionRepository.LegalHoldRow targetHold,
            List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds) {
        List<String> resourceTypes = legalHoldPurgeResourceTypes(targetHold.scope());
        PurgeAssessment withTarget = assessPurge(
                candidates, targetHold.scope(), resourceTypes, List.of(targetHold));
        PurgeAssessment afterRelease = assessPurge(
                candidates, targetHold.scope(), resourceTypes, otherActiveHolds);
        Map<String, Long> affected = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes, row -> true);
        Map<String, Long> currentlyHeld = releaseResourceCounts(
                withTarget.snapshotRows(), resourceTypes,
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::held);
        Map<String, Long> purgeSafe = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes,
                row -> !row.held() && "DWP_SANDBOX".equals(row.providerType()));
        Map<String, Long> stillProtected = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes,
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::held);
        Map<String, Long> providerRequired = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes,
                row -> !row.held() && !"DWP_SANDBOX".equals(row.providerType()));
        return new LegalHoldReleaseAssessment(
                affected, currentlyHeld, purgeSafe, stillProtected, providerRequired,
                afterRelease.fingerprintRows());
    }

    List<String> legalHoldPurgeResourceTypes(Map<String, Object> scope) {
        if (!scope.containsKey("resourceTypes")) {
            return normalizedResources(List.copyOf(PURGE_RESOURCE_TYPES));
        }
        return stringSelectors(scope.get("resourceTypes")).stream()
                .filter(PURGE_RESOURCE_TYPES::contains).sorted().toList();
    }

    Map<String, Long> releaseResourceCounts(
            List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> rows,
            List<String> resourceTypes,
            java.util.function.Predicate<AdminMailCompletionRepository.PurgeCandidateSnapshotRow>
                    included) {
        Set<String> requested = Set.copyOf(resourceTypes);
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> selected = rows.stream()
                .filter(included).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("THREADS", requested.contains("THREADS") ? (long) selected.size() : 0L);
        counts.put("MESSAGES", requested.contains("MESSAGES") ? selected.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::messageCount)
                .sum() : 0L);
        counts.put("ATTACHMENTS", requested.contains("ATTACHMENTS") ? selected.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::attachmentCount)
                .sum() : 0L);
        counts.put("DRAFTS", requested.contains("DRAFTS") ? selected.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::draftCount)
                .sum() : 0L);
        return Map.copyOf(counts);
    }

    Map<String, Long> releaseCounts(Map<String, Object> stored) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String resource : List.of("THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS")) {
            Object value = stored.get(resource);
            if (!(value instanceof Number number) || number.longValue() < 0) {
                conflict("LEGAL_HOLD_RELEASE_PREVIEW_EVIDENCE_INVALID");
            }
            counts.put(resource, ((Number) value).longValue());
        }
        return Map.copyOf(counts);
    }

    String legalHoldReleaseSnapshotFingerprint(
            long tenantId, AdminMailCompletionRepository.LegalHoldRow hold,
            long policyVersion, OffsetDateTime retentionBoundary,
            List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds,
            LegalHoldReleaseAssessment assessment) {
        return fingerprints.digest(
                "LEGAL_HOLD_RELEASE_SNAPSHOT", tenantId, hold.id(), hold.version(),
                hold.scope(), hold.status(), hold.startsAt(), hold.expiresAt(),
                policyVersion, retentionBoundary,
                otherActiveHolds.stream().map(other -> List.of(
                        other.id(), other.scope(), other.version())).toList(),
                assessment.fingerprintRows(), assessment.affected(),
                assessment.currentlyHeld(), assessment.purgeSafe(),
                assessment.stillProtected(), assessment.providerRequired());
    }

    boolean releaseAssessmentMatches(
            AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview,
            LegalHoldReleaseAssessment assessment) {
        return releaseCounts(preview.affectedCounts()).equals(assessment.affected())
                && releaseCounts(preview.currentlyHeldCounts())
                        .equals(assessment.currentlyHeld())
                && releaseCounts(preview.purgeSafeCounts()).equals(assessment.purgeSafe())
                && releaseCounts(preview.protectedCounts()).equals(assessment.stillProtected())
                && releaseCounts(preview.providerRequiredCounts())
                        .equals(assessment.providerRequired());
    }

}
