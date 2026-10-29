package com.dwp.services.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
class MailSyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(MailSyncJobWorker.class);

    private final MailSyncJobRepository jobs;
    private final MailSyncExecutor executor;
    private final MailMetrics metrics;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final String workerId;

    MailSyncJobWorker(
            MailSyncJobRepository jobs,
            MailSyncExecutor executor,
            MailMetrics metrics,
            @Value("${dwp.platform.mail.sync-worker.enabled:true}") boolean enabled,
            @Value("${dwp.platform.mail.sync-worker.batch-size:10}") int batchSize,
            @Value("${dwp.platform.mail.sync-worker.lease-seconds:120}") int leaseSeconds,
            @Value("${dwp.platform.mail.sync-worker.worker-id:${HOSTNAME:local}}")
            String workerName) {
        this.jobs = jobs;
        this.executor = executor;
        this.metrics = metrics;
        this.enabled = enabled;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.workerId = workerName + ':' + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${dwp.platform.mail.sync-worker.poll-delay-ms:1000}")
    void executePending() {
        if (!enabled) return;
        try {
            metrics.syncLeaseRecovery(jobs.releaseExpiredLeases());
            for (MailSyncJobRepository.SyncJob job
                    : jobs.claim(workerId, batchSize, leaseSeconds)) {
                execute(job);
            }
        } catch (RuntimeException pollingFailure) {
            metrics.syncJob("poll_failed", Duration.ZERO);
            log.error("Mail sync polling failed", pollingFailure);
        }
    }

    private void execute(MailSyncJobRepository.SyncJob job) {
        long startedAt = System.nanoTime();
        try {
            MailSyncExecutor.ExecutionResult result = executor.execute(job);
            if (result.continuationRequired()) {
                if (!jobs.completeContinuation(job, workerId, result)) {
                    throw new IllegalStateException("SYNC_LEASE_LOST");
                }
                metrics.syncJob("continued", elapsed(startedAt));
                return;
            }
            if (!jobs.completeSuccess(job, workerId, result)) {
                throw new IllegalStateException("SYNC_LEASE_LOST");
            }
            metrics.syncJob("succeeded", elapsed(startedAt));
        } catch (MailSyncExecutor.SyncWorkFailure failure) {
            MailSyncJobRepository.FailureDisposition disposition = jobs.completeFailure(
                    job, workerId, failure.code(), failure.terminal(),
                    failure.classification(), failure.retryAt(), failure.resetEvidence());
            metrics.syncJob(
                    disposition.terminal() ? "dead_letter" : "retry_wait",
                    elapsed(startedAt));
            log.warn("Mail sync failed jobId={} attempt={} code={}",
                    job.jobId(), job.attemptCount(), failure.code());
        } catch (RuntimeException failure) {
            MailSyncJobRepository.FailureDisposition disposition = jobs.completeFailure(
                    job, workerId, "SYNC_EXECUTION_FAILED", false,
                    "TRANSIENT", null, java.util.Map.of());
            metrics.syncJob(
                    disposition.terminal() ? "dead_letter" : "retry_wait",
                    elapsed(startedAt));
            log.warn("Mail sync failed jobId={} attempt={}",
                    job.jobId(), job.attemptCount(), failure);
        }
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
