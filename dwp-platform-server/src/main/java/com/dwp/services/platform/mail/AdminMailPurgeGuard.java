package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Revalidates the approved immutable candidate set while holding the retention lock. */
@Component
class AdminMailPurgeGuard {

    private static final Set<String> SCOPE_KEYS = Set.of(
            "tenant", "accountId", "accountIds", "threadId", "threadIds", "resourceTypes");
    private static final Set<String> CASCADE_RESOURCE_TYPES = Set.of(
            "THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS");

    private final AdminMailCompletionRepository repository;

    AdminMailPurgeGuard(AdminMailCompletionRepository repository) {
        this.repository = repository;
    }

    void requireSafe(AdminMailCompletionRepository.PurgeLeaseRow job) {
        AdminMailCompletionRepository.PurgePreviewRow preview = repository
                .purgePreview(job.tenantId(), job.snapshotId())
                .orElseThrow(() -> blocked("PURGE_PREVIEW_MISSING"));
        if (repository.policy(job.tenantId()).version() != preview.policyVersion()) {
            throw blocked("PURGE_POLICY_CHANGED");
        }
        Set<String> requestedResources = Set.copyOf(preview.resourceTypes());
        if (requestedResources.isEmpty()
                || !CASCADE_RESOURCE_TYPES.containsAll(requestedResources)) {
            throw blocked("PURGE_RESOURCE_SET_CHANGED");
        }
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows =
                repository.purgeCandidateRows(job.tenantId(), job.snapshotId());
        List<UUID> approvedEligible = snapshotRows.stream()
                .filter(row -> !row.held())
                .map(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::threadId)
                .toList();
        if (!approvedEligible.equals(job.candidateThreadIds())) {
            throw blocked("PURGE_JOB_EXCEEDS_APPROVED_SNAPSHOT");
        }

        List<AdminMailCompletionRepository.CandidateRow> liveRows = repository
                .purgeCandidates(job.tenantId(), preview.before()).rows().stream()
                .filter(row -> matchesSelectors(preview.scope(), row))
                .toList();
        Set<UUID> snapshotIds = snapshotRows.stream()
                .map(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::threadId)
                .collect(java.util.stream.Collectors.toSet());
        if (liveRows.stream().map(AdminMailCompletionRepository.CandidateRow::threadId)
                .anyMatch(id -> !snapshotIds.contains(id))) {
            throw blocked("PURGE_CANDIDATE_SET_EXPANDED");
        }
        Map<UUID, AdminMailCompletionRepository.CandidateRow> liveById = liveRows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        AdminMailCompletionRepository.CandidateRow::threadId, row -> row));
        List<AdminMailCompletionRepository.LegalHoldRow> holds =
                repository.activeLegalHolds(job.tenantId());
        Set<UUID> deletionReceipts = repository.purgeDeletionReceiptIds(
                job.tenantId(), job.id());
        if (!approvedEligible.containsAll(deletionReceipts)) {
            throw blocked("PURGE_DELETE_RECEIPT_EXCEEDS_APPROVED_SNAPSHOT");
        }
        for (AdminMailCompletionRepository.PurgeCandidateSnapshotRow snapshot : snapshotRows) {
            if (snapshot.held()) continue;
            AdminMailCompletionRepository.CandidateRow live = liveById.get(snapshot.threadId());
            if (live == null) {
                if (deletionReceipts.contains(snapshot.threadId())) continue;
                throw blocked("PURGE_CANDIDATE_MISSING_OR_NO_LONGER_ELIGIBLE");
            }
            if (deletionReceipts.contains(snapshot.threadId())) {
                throw blocked("PURGE_DELETED_CANDIDATE_REAPPEARED");
            }
            if (!live.accountId().equals(snapshot.accountId())
                    || live.messageCount() != snapshot.messageCount()
                    || live.attachmentCount() != snapshot.attachmentCount()
                    || live.draftCount() != snapshot.draftCount()
                    || !live.providerType().equals(snapshot.providerType())) {
                throw blocked("PURGE_CANDIDATE_EVIDENCE_CHANGED");
            }
            if (live.immutableEvidenceBlocked()) {
                throw blocked("PURGE_IMMUTABLE_EVIDENCE_ADDED");
            }
            if (holds.stream().anyMatch(
                    hold -> holdMatches(hold.scope(), live, requestedResources))) {
                throw blocked("PURGE_LEGAL_HOLD_ADDED");
            }
        }
    }

    private boolean matchesSelectors(
            Map<String, Object> scope, AdminMailCompletionRepository.CandidateRow row) {
        if (Boolean.TRUE.equals(scope.get("tenant"))) return true;
        Set<UUID> accounts = uuids(scope, "accountId", "accountIds");
        Set<UUID> threads = uuids(scope, "threadId", "threadIds");
        return (accounts.isEmpty() || accounts.contains(row.accountId()))
                && (threads.isEmpty() || threads.contains(row.threadId()));
    }

    private boolean holdMatches(
            Map<String, Object> scope,
            AdminMailCompletionRepository.CandidateRow row,
            Set<String> resources) {
        if (scope == null || scope.isEmpty() || !SCOPE_KEYS.containsAll(scope.keySet())) {
            return true;
        }
        try {
            if (!matchesSelectors(scope, row)) return false;
            if (!scope.containsKey("resourceTypes")) return true;
            Object raw = scope.get("resourceTypes");
            if (!(raw instanceof List<?> values)) return true;
            return values.stream().map(String::valueOf)
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .anyMatch(resources::contains);
        } catch (RuntimeException invalidLegacyScope) {
            return true;
        }
    }

    private Set<UUID> uuids(Map<String, Object> scope, String singular, String plural) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        if (scope.get(singular) != null) {
            result.add(UUID.fromString(String.valueOf(scope.get(singular))));
        }
        if (scope.get(plural) instanceof List<?> values) {
            values.forEach(value -> result.add(UUID.fromString(String.valueOf(value))));
        }
        return Set.copyOf(result);
    }

    private PurgeBlockedException blocked(String code) {
        return new PurgeBlockedException(code);
    }

    static final class PurgeBlockedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        PurgeBlockedException(String code) { super(code); }
    }
}
