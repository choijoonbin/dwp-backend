package com.dwp.services.messaging.retention;

import com.dwp.services.messaging.attachment.AttachmentStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public final class MessagingRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MessagingRetentionService.class);

    private final MessagingRetentionRepository repository;
    private final AttachmentStorage storage;
    private final MessagingRetentionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final UUID workerId;
    private final Counter purgedMessages;
    private final Counter purgedContinuityPayloads;
    private final Counter blockedRuns;
    private final Counter failedRuns;
    private final Counter attachmentDeletionFailures;
    private final Timer runLatency;

    @Autowired
    public MessagingRetentionService(
            MessagingRetentionRepository repository,
            AttachmentStorage storage,
            MessagingRetentionProperties properties,
            TransactionTemplate transactions,
            MeterRegistry meters) {
        this(repository, storage, properties, transactions, meters, Clock.systemUTC(), UUID.randomUUID());
    }

    MessagingRetentionService(
            MessagingRetentionRepository repository,
            AttachmentStorage storage,
            MessagingRetentionProperties properties,
            TransactionTemplate transactions,
            MeterRegistry meters,
            Clock clock,
            UUID workerId) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.workerId = workerId;
        this.purgedMessages = Counter.builder("dwp.messaging.retention.messages.purged")
                .description("Messages whose governed content was purged after retention expiry")
                .register(meters);
        this.purgedContinuityPayloads = Counter.builder(
                        "dwp.messaging.retention.continuity.payloads.purged")
                .description("Expired drafts deleted and terminal scheduled payloads redacted")
                .register(meters);
        this.blockedRuns = Counter.builder("dwp.messaging.retention.runs.blocked")
                .description("Retention runs blocked by a missing safety authority")
                .register(meters);
        this.failedRuns = Counter.builder("dwp.messaging.retention.runs.failed")
                .description("Retention runs that failed and require an operator review")
                .register(meters);
        this.attachmentDeletionFailures = Counter.builder(
                        "dwp.messaging.retention.attachments.deletion.failed")
                .description("Provider attachment deletions left pending for retry")
                .register(meters);
        this.runLatency = Timer.builder("dwp.messaging.retention.run.duration")
                .description("Tenant retention run duration")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${dwp.messaging.retention.initial-delay:PT5M}",
            fixedDelayString = "${dwp.messaging.retention.interval:PT1H}")
    public void runScheduled() {
        if (!properties.enabled()) return;
        for (MessagingRetentionRepository.TenantRetentionPolicy policy : repository.tenantPolicies()) {
            try {
                runTenant(policy);
            } catch (RuntimeException exception) {
                failedRuns.increment();
                log.error(
                        "Messaging retention tenant could not start tenantId={} failureCode={}",
                        policy.tenantId(), boundedFailureCode(exception), exception);
            }
        }
    }

    void runTenant(MessagingRetentionRepository.TenantRetentionPolicy policy) {
        long started = System.nanoTime();
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(
                clock.instant().minusSeconds(policy.retentionDays() * 86_400L), ZoneOffset.UTC);
        Optional<UUID> run = transactions.execute(status -> repository.tryStartRun(
                policy.tenantId(), properties.mode().name(), cutoff, workerId,
                properties.leaseDuration()));
        if (run == null || run.isEmpty()) return;
        UUID runId = run.orElseThrow();
        long purged = 0;
        long candidateCount = 0;
        long heldCount = 0;
        try {
            if (properties.paused()) {
                complete(policy.tenantId(), runId, "PAUSED", 0, 0, 0,
                        repository.pendingAttachmentDeletionCount(policy.tenantId()),
                        "OPERATOR_PAUSED");
                return;
            }
            String blockedReason = properties.blockedReason();
            if (blockedReason != null) {
                blockedRuns.increment();
                complete(policy.tenantId(), runId, "BLOCKED", 0, 0, 0,
                        repository.pendingAttachmentDeletionCount(policy.tenantId()),
                        blockedReason);
                return;
            }

            MessagingRetentionRepository.CandidateStats candidates =
                    repository.candidateStats(policy.tenantId(), cutoff);
            candidateCount = candidates.candidateCount();
            heldCount = candidates.heldCount();
            if (properties.mode() == MessagingRetentionProperties.ExecutionMode.DRY_RUN) {
                complete(
                        policy.tenantId(), runId, "COMPLETED",
                        candidateCount, 0, heldCount,
                        repository.pendingAttachmentDeletionCount(policy.tenantId()), null);
                return;
            }

            requireLease(runId);
            int deletionBudget = properties.maximumAttachmentDeletesPerRun();
            int attemptedAttachmentDeletes = drainAttachmentBacklog(
                    policy.tenantId(), deletionBudget);
            deletionBudget -= attemptedAttachmentDeletes;
            long pendingAttachments = repository.pendingAttachmentDeletionCount(policy.tenantId());
            if (pendingAttachments > 0) {
                failedRuns.increment();
                complete(policy.tenantId(), runId, "FAILED", candidateCount, 0, heldCount,
                        pendingAttachments, "ATTACHMENT_DELETION_BACKLOG");
                return;
            }
            if (attemptedAttachmentDeletes > 0 && deletionBudget == 0) {
                failedRuns.increment();
                complete(policy.tenantId(), runId, "FAILED", candidateCount, 0, heldCount,
                        0, "ATTACHMENT_DELETE_RATE_LIMIT_REACHED");
                return;
            }

            int messageBatchesRemaining = properties.maximumBatchesPerRun();
            while (messageBatchesRemaining > 0) {
                requireLease(runId);
                List<UUID> messageIds = repository.nextCandidates(
                        policy.tenantId(), cutoff, properties.batchSize());
                if (messageIds.isEmpty()) break;
                messageBatchesRemaining--;
                for (UUID messageId : messageIds) {
                    PurgeOutcome outcome = transactions.execute(status -> purgeOne(
                            policy.tenantId(), runId, messageId, cutoff));
                    if (outcome == PurgeOutcome.PURGED) {
                        purged++;
                        purgedMessages.increment();
                    }
                }
                if (messageIds.size() < properties.batchSize()) break;
            }
            int continuityBatchesRemaining = properties.maximumBatchesPerRun();
            while (continuityBatchesRemaining > 0) {
                requireLease(runId);
                MessagingRetentionRepository.ContinuityPurgeResult continuity =
                        transactions.execute(status -> repository.purgeContinuityContent(
                                policy.tenantId(), cutoff, properties.batchSize()));
                if (continuity == null || continuity.total() == 0) break;
                continuityBatchesRemaining--;
                purged += continuity.total();
                purgedContinuityPayloads.increment(continuity.total());
                if (continuity.total() < properties.batchSize()) break;
            }
            requireLease(runId);
            if (deletionBudget > 0) {
                drainAttachmentBacklog(policy.tenantId(), deletionBudget);
            }
            pendingAttachments = repository.pendingAttachmentDeletionCount(policy.tenantId());
            if (pendingAttachments > 0) {
                failedRuns.increment();
                complete(policy.tenantId(), runId, "FAILED", candidateCount, purged, heldCount,
                        pendingAttachments, "ATTACHMENT_DELETION_BACKLOG");
                return;
            }
            complete(
                    policy.tenantId(), runId, "COMPLETED",
                    candidateCount, purged, heldCount, 0, null);
        } catch (RuntimeException exception) {
            failedRuns.increment();
            String failureCode = boundedFailureCode(exception);
            try {
                complete(policy.tenantId(), runId, "FAILED", candidateCount, purged, heldCount,
                        repository.pendingAttachmentDeletionCount(policy.tenantId()), failureCode);
            } catch (RuntimeException evidenceFailure) {
                exception.addSuppressed(evidenceFailure);
            }
            log.error(
                    "Messaging retention run failed tenantId={} runId={} failureCode={}",
                    policy.tenantId(), runId, failureCode, exception);
        } finally {
            runLatency.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public RetentionStatus status(long tenantId) {
        MessagingRetentionRepository.RetentionRun latest = repository.latestRun(tenantId);
        String reasonCode = properties.blockedReason();
        if (reasonCode == null && latest != null && "FAILED".equals(latest.state())) {
            reasonCode = latest.failureCode();
        }
        return new RetentionStatus(
                properties.enabled(), properties.runtimeState(), reasonCode,
                latest == null ? null : latest.startedAt(),
                latest == null ? null : latest.finishedAt(),
                latest == null ? null : latest.state(),
                latest == null ? null : latest.executionMode(),
                latest == null ? 0 : latest.candidateCount(),
                latest == null ? 0 : latest.purgedCount(),
                latest == null ? 0 : latest.skippedHoldCount(),
                repository.pendingAttachmentDeletionCount(tenantId));
    }

    private PurgeOutcome purgeOne(
            long tenantId, UUID runId, UUID messageId, OffsetDateTime cutoff) {
        Optional<MessagingRetentionRepository.LockedMessage> locked =
                repository.lockEligibleMessage(tenantId, messageId, cutoff);
        if (locked.isEmpty()) return PurgeOutcome.SKIPPED;
        return repository.purgeMessageContent(tenantId, runId, messageId) == 1
                ? PurgeOutcome.PURGED
                : PurgeOutcome.SKIPPED;
    }

    private int drainAttachmentBacklog(long tenantId, int limit) {
        int attempted = 0;
        for (MessagingRetentionRepository.PendingAttachmentDeletion deletion
                : repository.pendingAttachmentDeletions(tenantId, limit)) {
            attempted++;
            try {
                storage.delete(deletion.objectKey());
                transactions.executeWithoutResult(status ->
                        repository.completeAttachmentDeletion(deletion.deletionId()));
            } catch (RuntimeException exception) {
                attachmentDeletionFailures.increment();
                String failureCode = boundedFailureCode(exception);
                transactions.executeWithoutResult(status ->
                        repository.failAttachmentDeletion(deletion.deletionId(), failureCode));
            }
        }
        return attempted;
    }

    private void requireLease(UUID runId) {
        Boolean renewed = transactions.execute(status -> repository.renewLease(
                runId, workerId, properties.leaseDuration()));
        if (!Boolean.TRUE.equals(renewed)) {
            throw new IllegalStateException("Retention execution lease was lost.");
        }
    }

    private void complete(
            long tenantId,
            UUID runId,
            String state,
            long candidateCount,
            long purgedCount,
            long skippedHoldCount,
            long pendingAttachmentDeletionCount,
            String failureCode) {
        transactions.executeWithoutResult(status -> {
            if (!repository.finishRun(
                    runId, workerId, state, candidateCount, purgedCount, skippedHoldCount,
                    pendingAttachmentDeletionCount, failureCode)) {
                throw new IllegalStateException("Retention execution lease was lost.");
            }
            repository.recordAudit(
                    tenantId, runId, state, candidateCount, purgedCount, skippedHoldCount,
                    pendingAttachmentDeletionCount, failureCode);
        });
    }

    private String boundedFailureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "_");
        return name.isBlank() ? "RETENTION_EXECUTION_FAILED"
                : name.substring(0, Math.min(name.length(), 80)).toUpperCase(java.util.Locale.ROOT);
    }

    private enum PurgeOutcome {
        PURGED,
        SKIPPED
    }

    public record RetentionStatus(
            boolean configured,
            String state,
            String reasonCode,
            OffsetDateTime lastStartedAt,
            OffsetDateTime lastVerifiedAt,
            String lastRunState,
            String lastRunExecutionMode,
            long candidateCount,
            long purgedCount,
            long skippedHoldCount,
            long pendingAttachmentDeletionCount) { }
}
