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

class AdminMailCompletionProjectionSupport extends AdminMailCompletionServiceBase {

    AdminMailCompletionProjectionSupport(
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

    List<PurgeJob> purgeJobs(long tenantId) {
        return repository.purgeJobs(tenantId, 50).stream().map(this::purgeJob).toList();
    }

    PurgePreview purgePreview(
            long tenantId, AdminMailCompletionRepository.PurgePreviewRow row) {
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> candidates =
                repository.purgeCandidateRows(tenantId, row.id());
        Map<String, Long> resourceCounts = new LinkedHashMap<>();
        resourceCounts.put("THREADS", (long) candidates.size());
        resourceCounts.put("MESSAGES", candidates.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::messageCount)
                .sum());
        resourceCounts.put("ATTACHMENTS", candidates.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::attachmentCount)
                .sum());
        resourceCounts.put("DRAFTS", candidates.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::draftCount)
                .sum());
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> heldCandidates =
                candidates.stream().filter(
                        AdminMailCompletionRepository.PurgeCandidateSnapshotRow::held).toList();
        Map<String, Long> heldResourceCounts = new LinkedHashMap<>();
        heldResourceCounts.put("THREADS", (long) heldCandidates.size());
        heldResourceCounts.put("MESSAGES", heldCandidates.stream().mapToLong(
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::messageCount).sum());
        heldResourceCounts.put("ATTACHMENTS", heldCandidates.stream().mapToLong(
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::attachmentCount).sum());
        heldResourceCounts.put("DRAFTS", heldCandidates.stream().mapToLong(
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::draftCount).sum());
        Map<String, Long> exclusionReasonCounts = new LinkedHashMap<>();
        exclusionReasonCounts.put("LEGAL_HOLD", candidates.stream().filter(candidate ->
                Boolean.TRUE.equals(candidate.evidence().get("legalHoldBlocked"))).count());
        exclusionReasonCounts.put("IMMUTABLE_EVIDENCE", candidates.stream().filter(candidate ->
                Boolean.TRUE.equals(candidate.evidence().get("immutableEvidenceBlocked"))).count());
        return new PurgePreview(
                row.id(), row.fingerprint(), row.total(), row.held(), row.eligible(),
                row.partialSources(), row.createdAt(), row.expiresAt(), row.policyVersion(),
                repository.distinctApprovals(tenantId, row.id(), row.policyVersion()),
                Map.copyOf(resourceCounts), row.resourceTypes(), row.scope(), row.before(),
                Map.copyOf(heldResourceCounts), Map.copyOf(exclusionReasonCounts));
    }

    PurgeApproval purgeApproval(
            long tenantId, AdminMailCompletionRepository.PurgeApprovalRow row) {
        return new PurgeApproval(
                row.id(), row.snapshotId(), repository.distinctApprovals(
                        tenantId, row.snapshotId(), row.policyVersion()),
                row.policyVersion(), row.approvedAt());
    }

    PurgePreview purgePreviewProjection(
            PurgePreview preview, boolean includeSensitiveScope) {
        if (includeSensitiveScope) return preview;
        return new PurgePreview(
                preview.candidateSnapshotId(), preview.fingerprint(),
                preview.totalCandidates(), preview.heldCount(), preview.eligibleCount(),
                preview.partialSources(), preview.generatedAt(), preview.expiresAt(),
                preview.policyVersion(), preview.distinctApproverCount(), Map.of(),
                preview.resourceTypes(), Map.of(), null, Map.of(), Map.of());
    }

    PurgeJob purgeJob(AdminMailCompletionRepository.PurgeJobRow row) {
        return new PurgeJob(
                row.id(), row.snapshotId(), row.state(), row.deletedThreads(),
                row.deletedMessages(), row.steps(), row.verification(), row.errorCode(),
                row.startedAt(), row.completedAt());
    }

    PurgeJob purgeJobProjection(
            PurgeJob job, boolean includeSensitiveEvidence) {
        if (includeSensitiveEvidence) return job;
        return new PurgeJob(
                job.jobId(), job.candidateSnapshotId(), job.state(),
                job.deletedThreads(), job.deletedMessages(), List.of(),
                job.verificationState(), null, job.startedAt(), job.completedAt());
    }

    void requireLivePreview(
            AdminMailCompletionRepository.PurgePreviewRow preview, long policyVersion) {
        if (preview.expiresAt().isBefore(now())) conflict("PURGE_PREVIEW_EXPIRED");
        if (preview.policyVersion() != policyVersion) conflict("POLICY_VERSION_CONFLICT");
    }

    OffsetDateTime retentionBoundary(OffsetDateTime requested, int retentionDays) {
        OffsetDateTime policyBoundary = now().minusDays(retentionDays);
        return requested.isBefore(policyBoundary) ? requested : policyBoundary;
    }

    String purgeFingerprint(
            long tenantId, Map<String, Object> scope, List<String> resourceTypes,
            OffsetDateTime before, long policyVersion,
            List<AdminMailCompletionRepository.LegalHoldRow> activeHolds,
            PurgeAssessment assessment) {
        return fingerprints.digest(
                "PURGE_SNAPSHOT", tenantId, scope, normalizedResources(resourceTypes), before,
                policyVersion,
                activeHolds.stream().map(hold -> List.of(
                        hold.id(), hold.scope(), hold.version())).toList(),
                assessment.fingerprintRows(), assessment.total(), assessment.held(),
                assessment.eligible());
    }

    PurgeAssessment assessPurge(
            AdminMailCompletionRepository.CandidateSet candidates,
            Map<String, Object> scope,
            List<String> resourceTypes,
            List<AdminMailCompletionRepository.LegalHoldRow> activeHolds) {
        Set<String> requested = Set.copyOf(normalizedResources(resourceTypes));
        List<AdminMailCompletionRepository.CandidateRow> scoped = candidates.rows().stream()
                .filter(row -> matchesSelectors(scope, row))
                .toList();
        int total = 0;
        int held = 0;
        int blockedThreads = 0;
        int blockedMessages = 0;
        boolean external = false;
        List<UUID> eligibleThreadIds = new ArrayList<>();
        List<List<Object>> fingerprintRows = new ArrayList<>();
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows =
                new ArrayList<>();
        for (AdminMailCompletionRepository.CandidateRow row : scoped) {
            int units = candidateUnits(row, requested);
            List<UUID> matchingHoldIds = activeHolds.stream()
                    .filter(hold -> holdMatches(hold.scope(), row, requested))
                    .map(AdminMailCompletionRepository.LegalHoldRow::id).toList();
            boolean legalHoldBlocked = !matchingHoldIds.isEmpty();
            boolean blocked = row.immutableEvidenceBlocked() || legalHoldBlocked;
            total += units;
            if (blocked) {
                held += units;
                blockedThreads++;
                blockedMessages += row.messageCount();
            } else if (units > 0) {
                eligibleThreadIds.add(row.threadId());
                external |= !"DWP_SANDBOX".equals(row.providerType());
            }
            fingerprintRows.add(List.of(
                    row.threadId(), row.accountId(), row.messageCount(),
                    row.attachmentCount(), row.draftCount(), row.providerType(), blocked));
            snapshotRows.add(new AdminMailCompletionRepository.PurgeCandidateSnapshotRow(
                    row.threadId(), row.accountId(), row.messageCount(),
                    row.attachmentCount(), row.draftCount(), row.providerType(), blocked,
                    matchingHoldIds,
                    Map.of("immutableEvidenceBlocked", row.immutableEvidenceBlocked(),
                            "legalHoldBlocked", legalHoldBlocked,
                            "resourceTypes", normalizedResources(resourceTypes))));
        }
        return new PurgeAssessment(
                total, held, total - held, List.copyOf(eligibleThreadIds),
                blockedThreads, blockedMessages, external, List.copyOf(fingerprintRows),
                List.copyOf(snapshotRows));
    }

    int candidateUnits(
            AdminMailCompletionRepository.CandidateRow row, Set<String> requested) {
        int total = 0;
        if (requested.contains("THREADS")) total++;
        if (requested.contains("MESSAGES")) total += row.messageCount();
        if (requested.contains("ATTACHMENTS")) total += row.attachmentCount();
        if (requested.contains("DRAFTS")) total += row.draftCount();
        return total;
    }

    boolean matchesSelectors(
            Map<String, Object> scope, AdminMailCompletionRepository.CandidateRow row) {
        if (Boolean.TRUE.equals(scope.get("tenant"))) return true;
        Set<UUID> accounts = uuidSelectors(scope, "accountId", "accountIds");
        Set<UUID> threads = uuidSelectors(scope, "threadId", "threadIds");
        return (accounts.isEmpty() || accounts.contains(row.accountId()))
                && (threads.isEmpty() || threads.contains(row.threadId()));
    }

    boolean holdMatches(
            Map<String, Object> holdScope,
            AdminMailCompletionRepository.CandidateRow row,
            Set<String> requestedResources) {
        if (holdScope == null || holdScope.isEmpty()
                || !SCOPE_KEYS.containsAll(holdScope.keySet())) {
            return true;
        }
        try {
            if (!matchesSelectors(holdScope, row)) return false;
            if (!holdScope.containsKey("resourceTypes")) return true;
            Set<String> heldResources = Set.copyOf(stringSelectors(holdScope.get("resourceTypes")));
            return heldResources.stream().anyMatch(requestedResources::contains);
        } catch (RuntimeException malformedLegacyScope) {
            return true;
        }
    }

    Set<UUID> uuidSelectors(
            Map<String, Object> scope, String singularKey, String pluralKey) {
        java.util.LinkedHashSet<UUID> values = new java.util.LinkedHashSet<>();
        Object singular = scope.get(singularKey);
        if (singular != null) values.add(UUID.fromString(String.valueOf(singular)));
        Object plural = scope.get(pluralKey);
        if (plural != null) {
            if (!(plural instanceof List<?> list)) throw new IllegalArgumentException("selector");
            for (Object value : list) values.add(UUID.fromString(String.valueOf(value)));
        }
        return Set.copyOf(values);
    }

    List<String> stringSelectors(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("selector");
        return list.stream().map(String::valueOf)
                .map(item -> item.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    record PurgeAssessment(
            int total, int held, int eligible, List<UUID> eligibleThreadIds,
            int blockedThreadCount, int blockedMessageCount,
            boolean hasExternalProvider, List<List<Object>> fingerprintRows,
            List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows) { }

    record LegalHoldReleaseAssessment(
            Map<String, Long> affected,
            Map<String, Long> currentlyHeld,
            Map<String, Long> purgeSafe,
            Map<String, Long> stillProtected,
            Map<String, Long> providerRequired,
            List<List<Object>> fingerprintRows) { }

    DeliveryAuditItem deliveryItem(
            long tenantId, AdminMailCompletionRepository.DeliveryRow row) {
        return deliveryItem(tenantId, row, true);
    }

    DeliveryAuditItem deliveryItem(
            long tenantId, AdminMailCompletionRepository.DeliveryRow row,
            boolean revealSensitive) {
        List<DeliveryAuditTimeline> timeline = new ArrayList<>();
        timeline.add(new DeliveryAuditTimeline(
                "COMMAND_ACCEPTED", "SUCCEEDED", row.createdAt(), "mail_delivery_outbox",
                "VERIFIED", null));
        if (row.attemptCount() > 0) {
            timeline.add(new DeliveryAuditTimeline(
                    "PROVIDER_ATTEMPT", timelineState(row), row.updatedAt(),
                    "mail_delivery_outbox", deliveryEvidenceState(row), row.errorCode()));
        }
        if (row.acceptedAt() != null) {
            timeline.add(new DeliveryAuditTimeline(
                    "PROVIDER_ACCEPTED", "SUCCEEDED", row.acceptedAt(),
                    row.providerType(), "VERIFIED", null));
        }
        repository.recoveryEvents(tenantId, row.id()).forEach(event -> timeline.add(
                new DeliveryAuditTimeline(
                        "ADMIN_" + event.action(), event.result(), event.occurredAt(),
                        "mail_delivery_recovery_events", "VERIFIED",
                        stringValue(event.evidence().get("errorCode")))));
        List<AdminMailCompletionRepository.DeliveryEvidenceRow> evidence =
                repository.deliveryEvidence(tenantId, row);
        if (evidence != null) evidence.forEach(event -> timeline.add(
                new DeliveryAuditTimeline(
                        event.stage(), event.state(), event.at(), event.source(),
                        event.evidenceState(), event.code())));
        timeline.sort(java.util.Comparator.comparing(DeliveryAuditTimeline::at));
        List<DeliveryAuditTimeline> projectedTimeline = timeline;
        if (!revealSensitive) {
            projectedTimeline = timeline.stream().map(event -> new DeliveryAuditTimeline(
                    event.stage(), event.state(), event.at(), "EVIDENCE_REDACTED",
                    event.evidenceState(), null)).toList();
        }
        boolean fresh = fresh(row.updatedAt(), now(), DELIVERY_EVIDENCE_FRESH);
        return new DeliveryAuditItem(
                row.id(), safeResourceReference("message", row.id().toString()),
                "SEND", revealSensitive ? "Mail administrator" : "REDACTED",
                revealSensitive ? row.accountName() : "REDACTED",
                revealSensitive ? row.providerType() : "REDACTED", deliveryStage(row),
                deliveryState(row), fresh && retryEligible(row) ? "ELIGIBLE" : "INELIGIBLE",
                providerDisposition(row), idempotencyState(row),
                fresh && reconcileEligible(row), fresh && cancelEligible(row),
                row.updatedAt(), revealSensitive ? nullToEmpty(row.correlationId()) : "",
                projectedTimeline, row.version());
    }

    String deliveryStage(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null) return "ACCEPTED_BY_PROVIDER";
        if (deliveryBlockedByAccess(row)) return "BLOCKED_BY_ACCESS";
        return switch (row.status()) {
            case "QUEUED", "RETRY_WAIT" -> "OUTBOX";
            case "LEASED" -> row.leaseExpiresAt() != null && row.leaseExpiresAt().isBefore(now())
                    ? "UNKNOWN" : "PROVIDER_SUBMITTED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    String deliveryState(AdminMailCompletionRepository.DeliveryRow row) {
        if ("DELIVERED".equals(row.status())) return "ACCEPTED_BY_PROVIDER";
        if (deliveryBlockedByAccess(row)) return "BLOCKED_BY_ACCESS";
        if ("LEASED".equals(row.status())
                && row.leaseExpiresAt() != null && row.leaseExpiresAt().isBefore(now())) {
            return "UNKNOWN";
        }
        return switch (row.status()) {
            case "QUEUED", "RETRY_WAIT" -> "QUEUED";
            case "FAILED" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    String providerDisposition(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null && row.providerMessageRef() != null) return "ACCEPTED";
        if ("FAILED".equals(row.status()) && row.providerMessageRef() == null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode())) {
            return "NOT_ACCEPTED";
        }
        return "UNKNOWN";
    }

    String idempotencyState(AdminMailCompletionRepository.DeliveryRow row) {
        return retryEligible(row) ? "REPLAY_SAFE" : "UNKNOWN";
    }

    String timelineState(AdminMailCompletionRepository.DeliveryRow row) {
        if ("DELIVERED".equals(row.status())) return "SUCCEEDED";
        if (deliveryBlockedByAccess(row)) return "BLOCKED";
        if ("FAILED".equals(row.status())) return "FAILED";
        if ("CANCELLED".equals(row.status())) return "BLOCKED";
        if ("LEASED".equals(row.status()) && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isBefore(now())) return "UNKNOWN";
        return "PENDING";
    }

    String deliveryEvidenceState(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null && row.providerMessageRef() != null) return "VERIFIED";
        if ("FAILED".equals(row.status()) && row.providerMessageRef() == null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode())) return "VERIFIED";
        if ("LEASED".equals(row.status()) && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isBefore(now())) return "STALE";
        return "REPORTED";
    }

    boolean retryEligible(AdminMailCompletionRepository.DeliveryRow row) {
        return "FAILED".equals(row.status())
                && row.acceptedAt() == null
                && row.providerMessageRef() == null
                && row.providerThreadRef() == null
                && row.leaseOwner() == null
                && row.leaseExpiresAt() == null
                && !deliveryBlockedByAccess(row)
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode());
    }

    boolean deliveryBlockedByAccess(
            AdminMailCompletionRepository.DeliveryRow row) {
        String code = nullToEmpty(row.errorCode()).toUpperCase(Locale.ROOT);
        return DELIVERY_ACCESS_TERMINAL_ERRORS.contains(code)
                || code.startsWith("MAIL_SEND_AUTHORIZATION_")
                || code.startsWith("MAIL_ADAPTER_AUTHENTICATION_")
                || code.startsWith("MAIL_ADAPTER_CONFIGURATION_")
                || code.startsWith("MAIL_ADAPTER_READINESS_");
    }

    String deliveryRecoveryAuthorizationError(
            long tenantId, AdminMailCompletionRepository.DeliveryRow delivery) {
        AdminMailCompletionRepository.DeliveryAuthorizationRow authorization = repository
                .deliveryAuthorization(tenantId, delivery.id()).orElse(null);
        if (authorization == null) return "MAIL_SEND_AUTHORIZATION_REVOKED";

        MailTypes.ProviderType providerType;
        try {
            providerType = MailTypes.ProviderType.valueOf(authorization.providerType());
        } catch (RuntimeException invalidProvider) {
            return "MAIL_ADAPTER_NOT_DEPLOYED";
        }
        MailConnectorPort connector = connectorRegistry.connector(providerType).orElse(null);
        if (connector == null) return "MAIL_ADAPTER_NOT_DEPLOYED";

        MailConnectorPort.ConnectionContext context;
        try {
            context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(
                            Long.toString(tenantId), Long.toString(authorization.senderId()),
                            Set.of(), correlation(delivery.correlationId())),
                    authorization.connectionId(), authorization.secretReference(),
                    authorization.mailDomain());
            MailConnectorPort.Readiness readiness = connector.readiness(context);
            if (readiness.state() != MailConnectorPort.ReadinessState.READY) {
                return switch (readiness.state()) {
                    case AUTHENTICATION_REQUIRED ->
                            "MAIL_ADAPTER_AUTHENTICATION_REQUIRED";
                    case CONFIGURATION_REQUIRED ->
                            "MAIL_ADAPTER_CONFIGURATION_REQUIRED";
                    default -> "MAIL_ADAPTER_READINESS_" + readiness.state().name();
                };
            }
        } catch (RuntimeException unavailable) {
            return "MAIL_ADAPTER_READINESS_UNAVAILABLE";
        }

        Set<MailConnectorPort.Capability> capabilities = connector.manifest().capabilities();
        if (!capabilities.contains(MailConnectorPort.Capability.SEND)) {
            return "MAIL_ADAPTER_SEND_NOT_SUPPORTED";
        }
        if ("SEND_ON_BEHALF".equals(authorization.senderMode())
                && !capabilities.contains(MailConnectorPort.Capability.SEND_ON_BEHALF)) {
            return "MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED";
        }
        if (authorization.hasBcc()
                && !capabilities.contains(MailConnectorPort.Capability.BCC)) {
            return "MAIL_ADAPTER_BCC_NOT_SUPPORTED";
        }
        if ("HTML".equals(authorization.bodyFormat())
                && !capabilities.contains(MailConnectorPort.Capability.HTML_BODY)) {
            return "MAIL_ADAPTER_HTML_NOT_SUPPORTED";
        }
        if (authorization.attachmentCount() > 0
                && !capabilities.contains(MailConnectorPort.Capability.ATTACHMENTS)) {
            return "MAIL_ADAPTER_ATTACHMENTS_NOT_SUPPORTED";
        }
        return null;
    }

    boolean cancelEligible(AdminMailCompletionRepository.DeliveryRow row) {
        return Set.of("QUEUED", "RETRY_WAIT").contains(row.status())
                && row.acceptedAt() == null
                && row.providerMessageRef() == null
                && row.leaseOwner() == null
                && row.leaseExpiresAt() == null;
    }

    boolean reconcileEligible(AdminMailCompletionRepository.DeliveryRow row) {
        return "DWP_SANDBOX".equals(row.providerType())
                && "LEASED".equals(row.status())
                && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isBefore(now())
                && row.acceptedAt() == null
                && row.providerMessageRef() == null;
    }

    Map<String, Object> recoveryEvidence(
            AdminMailCompletionRepository.DeliveryRow before,
            AdminMailCompletionRepository.DeliveryRow after, String errorCode) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("beforeState", before.status());
        evidence.put("afterState", after.status());
        evidence.put("beforeVersion", before.version());
        evidence.put("afterVersion", after.version());
        evidence.put("providerDispositionBefore", providerDisposition(before));
        if (errorCode != null) evidence.put("errorCode", errorCode);
        return evidence;
    }

}
