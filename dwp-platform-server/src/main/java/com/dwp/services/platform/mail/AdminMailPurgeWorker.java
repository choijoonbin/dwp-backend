package com.dwp.services.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes accepted retention purges from durable, leased jobs. */
@Component
class AdminMailPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(AdminMailPurgeWorker.class);

    private final AdminMailCompletionRepository repository;
    private final AdminMailPurgeTransactions transactions;
    private final MailPurgeExecutionAuthority authority;
    private final MailPurgeDomainEventPort domainEvents;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final String workerId;

    @Autowired
    AdminMailPurgeWorker(
            AdminMailCompletionRepository repository,
            AdminMailPurgeTransactions transactions,
            MailPurgeExecutionAuthority authority,
            MailPurgeDomainEventPort domainEvents,
            @Value("${dwp.platform.mail.purge.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.purge.batch-size:5}") int batchSize,
            @Value("${dwp.platform.mail.purge.lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.platform.mail.purge.maximum-attempts:5}") int maximumAttempts,
            @Value("${dwp.platform.mail.purge.worker-id:${HOSTNAME:local}}") String workerName) {
        this.repository = repository;
        this.transactions = transactions;
        this.authority = authority;
        this.domainEvents = domainEvents;
        this.enabled = enabled;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
        this.workerId = workerName + ':' + UUID.randomUUID();
    }

    AdminMailPurgeWorker(
            AdminMailCompletionRepository repository,
            AdminMailPurgeTransactions transactions,
            MailPurgeExecutionAuthority authority,
            boolean enabled,
            int batchSize,
            int leaseSeconds,
            int maximumAttempts,
            String workerName) {
        this(repository, transactions, authority, null, enabled, batchSize,
                leaseSeconds, maximumAttempts, workerName);
    }

    @Scheduled(fixedDelayString = "${dwp.platform.mail.purge.poll-delay-ms:1000}")
    void executePending() {
        if (!enabled) return;
        try {
            repository.releaseExpiredPurgeLeases(maximumAttempts);
            for (AdminMailCompletionRepository.PurgeLeaseRow job
                    : repository.claimPurgeJobs(
                            workerId, batchSize, leaseSeconds, maximumAttempts)) {
                execute(job);
            }
        } catch (RuntimeException pollingFailure) {
            log.error("Mail purge polling failed", pollingFailure);
        }
    }

    private void execute(AdminMailCompletionRepository.PurgeLeaseRow job) {
        List<Map<String, Object>> steps = new ArrayList<>(job.steps());
        AdminMailCompletionRepository.DeleteCounts counts =
                new AdminMailCompletionRepository.DeleteCounts(0, 0, 0);
        try {
            java.util.Set<UUID> deletionReceipts = repository.purgeDeletionReceiptIds(
                    job.tenantId(), job.id());
            boolean localDeletionComplete = deletionReceipts.size()
                    == job.candidateThreadIds().size()
                    && deletionReceipts.containsAll(job.candidateThreadIds());
            if (localDeletionComplete) {
                counts = repository.purgeDeletionReceiptCounts(job.tenantId(), job.id());
                steps.add(step("RESUME_LOCAL_DELETE_CHECKPOINT", "SUCCEEDED", Map.of(
                        "deletedThreads", counts.threads(),
                        "deletedMessages", counts.messages(),
                        "evidenceSource", "mail_purge_deleted_candidate_receipts",
                        "previousStep", job.previousStep())));
                requireCheckpoint(job, "LOCAL_DELETE_CHECKPOINTED", steps, counts);
            } else {
                MailPurgeExecutionAuthority.Decision decision = authority.evaluate(
                        job.tenantId(), job.actorId(), job.id());
                steps.add(step("REVALIDATE_EXECUTION_AUTHORITY",
                        decision.state() == MailPurgeExecutionAuthority.State.ALLOWED
                                ? "SUCCEEDED" : "BLOCKED",
                        authorityEvidence(decision)));
                if (decision.state() != MailPurgeExecutionAuthority.State.ALLOWED) {
                    String state = decision.state() == MailPurgeExecutionAuthority.State.DENIED
                            ? "FAILED" : "UNKNOWN";
                    repository.completeLeasedPurgeJob(
                            job.tenantId(), job.id(), workerId, state, counts, steps,
                            "UNKNOWN", decision.reasonCode());
                    return;
                }
                counts = transactions.revalidateAndDelete(job);
                steps.add(step("DELETE_LOCAL_THREADS", "SUCCEEDED", Map.of(
                        "deletedThreads", counts.threads(),
                        "deletedMessages", counts.messages(),
                        "evidenceSource", "mail_threads/mail_messages"
                                + "+mail_purge_deleted_candidate_receipts")));
                requireCheckpoint(job, "LOCAL_DELETE_CHECKPOINTED", steps, counts);
            }

            steps.add(step("DELETE_PROVIDER_DATA", "SKIPPED", Map.of(
                    "evidenceSource", "approved purge preview",
                    "code", "NO_EXTERNAL_PROVIDER_DELETE_CONTRACT")));
            steps.add(step("QUEUE_ATTACHMENT_CLEANUP",
                    counts.attachmentCleanupCommands() > 0 ? "SUCCEEDED" : "SKIPPED",
                    Map.of("commands", counts.attachmentCleanupCommands(),
                            "evidenceSource", "sys_tenant_media_cleanup_outbox")));
            UUID durableEventId = domainEvents == null
                    ? null : domainEvents.record(job, counts);
            AdminMailCompletionRepository.PurgeEventEvidence event = durableEventId == null
                    ? repository.ensurePurgeCompletionEvent(
                            job.tenantId(), job.id(), job.actorId(), counts)
                    : repository.ensurePurgeCompletionEvent(
                            job.tenantId(), job.id(), job.actorId(), counts, durableEventId);
            boolean eventPublished = event.publishedAt() != null;
            steps.add(step("PUBLISH_PURGE_EVENT",
                    eventPublished ? "SUCCEEDED" : "UNKNOWN", Map.of(
                    "eventId", event.eventId(),
                    "publishAttempts", event.publishAttempts(),
                    "deliveryState", event.deliveryState(),
                    "evidenceSource", "sys_domain_event_outbox",
                    "code", eventPublished ? "PUBLISHED" : "PUBLICATION_PENDING")));
            requireCheckpoint(job, "SIDE_EFFECT_EVIDENCE_RECORDED", steps, counts);

            long remaining = repository.remainingPurgeCandidates(
                    job.tenantId(), job.candidateThreadIds());
            String verification = remaining == 0 ? "VERIFIED" : "FAILED";
            String state = remaining != 0 ? "FAILED"
                    : eventPublished ? "SUCCEEDED"
                    : job.attemptCount() >= maximumAttempts ? "FAILED" : "PARTIAL";
            String errorCode = remaining != 0 ? "PURGE_VERIFICATION_FAILED"
                    : eventPublished ? null : "PURGE_EVENT_PUBLICATION_PENDING";
            steps.add(step("VERIFY_LOCAL_ABSENCE", verification, Map.of(
                    "remainingThreads", remaining,
                    "evidenceSource", "mail_threads")));
            if (repository.completeLeasedPurgeJob(
                    job.tenantId(), job.id(), workerId, state, counts, steps,
                    verification, errorCode) != 1) {
                throw new IllegalStateException("PURGE_WORKER_LEASE_LOST");
            }
            repository.audit(
                    job.tenantId(), job.actorId(), "mail.purge.completed", "MAIL_PURGE_JOB",
                    job.id().toString(), null, Map.of("attempt", job.attemptCount()),
                    Map.of("result", state, "deletedThreads", counts.threads(),
                            "deletedMessages", counts.messages(), "remaining", remaining));
        } catch (RuntimeException failure) {
            String code = sanitize(failure);
            steps.add(step("WORKER_INTERRUPTED", "UNKNOWN", Map.of(
                    "errorCode", code, "attempt", job.attemptCount())));
            String state = job.attemptCount() >= maximumAttempts ? "FAILED" : "UNKNOWN";
            repository.completeLeasedPurgeJob(
                    job.tenantId(), job.id(), workerId, state, counts, steps,
                    "UNKNOWN", code);
            log.warn("Mail purge attempt failed jobId={} attempt={}",
                    job.id(), job.attemptCount(), failure);
        }
    }

    private void requireCheckpoint(
            AdminMailCompletionRepository.PurgeLeaseRow job,
            String checkpoint,
            List<Map<String, Object>> steps,
            AdminMailCompletionRepository.DeleteCounts counts) {
        if (repository.checkpointPurgeJob(
                job.tenantId(), job.id(), workerId, checkpoint, steps, counts) != 1) {
            throw new IllegalStateException("PURGE_WORKER_LEASE_LOST");
        }
    }

    private Map<String, Object> step(
            String name, String state, Map<String, Object> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("step", name);
        result.put("state", state);
        result.put("at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        result.putAll(evidence);
        return Map.copyOf(result);
    }

    private Map<String, Object> authorityEvidence(MailPurgeExecutionAuthority.Decision decision) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidenceSource", "dwp-auth-server/product-surface-authority");
        evidence.put("authorityState", decision.state().name());
        if (decision.evidenceRef() != null) evidence.put("evidenceRef", decision.evidenceRef());
        if (decision.revision() != null) evidence.put("authRevision", decision.revision());
        if (decision.reasonCode() != null) evidence.put("reasonCode", decision.reasonCode());
        return Map.copyOf(evidence);
    }

    private String sanitize(RuntimeException failure) {
        if (failure instanceof AdminMailPurgeGuard.PurgeBlockedException
                && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            return failure.getMessage();
        }
        if (failure instanceof DataAccessException) {
            return "LOCAL_DELETE_FAILED";
        }
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "PURGE_WORKER_FAILURE" : name;
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
