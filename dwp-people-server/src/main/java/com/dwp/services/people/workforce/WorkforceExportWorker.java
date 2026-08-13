package com.dwp.services.people.workforce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "dwp.people.exports.execution-enabled", havingValue = "true")
public class WorkforceExportWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkforceExportWorker.class);
    private static final Set<String> TERMINAL_STATES = Set.of(
            "CANCELLED", "COMPLETED", "FAILED", "EXPIRED");

    private final WorkforceExportRepository repository;
    private final WorkforceExportArtifactWriter artifactWriter;
    private final WorkforceExportService service;
    private final WorkforceExportPolicy policy;
    private final int batchSize;
    private final String workerReference;

    public WorkforceExportWorker(
            WorkforceExportRepository repository,
            WorkforceExportArtifactWriter artifactWriter,
            WorkforceExportService service,
            WorkforceExportPolicy policy,
            @Value("${dwp.people.exports.worker.batch-size:10}") int batchSize,
            @Value("${dwp.people.exports.worker.reference:${HOSTNAME:local}}")
                    String workerReference) {
        this.repository = repository;
        this.artifactWriter = artifactWriter;
        this.service = service;
        this.policy = policy;
        this.batchSize = Math.min(100, Math.max(1, batchSize));
        this.workerReference = workerReference;
    }

    @Scheduled(fixedDelayString = "${dwp.people.exports.worker.poll-interval-ms:5000}")
    public void processPending() {
        for (WorkforceExportRepository.RequestRow claimed
                : repository.claim(batchSize, workerReference)) {
            WorkforceExportRepository.RequestRow current = repository
                    .findForWorker(claimed.tenantId(), claimed.requestId())
                    .orElse(claimed);
            if ("CANCEL_REQUESTED".equals(current.lifecycleState())) {
                service.failWorkerAttempt(current, "CANCELLED", null, "CANCELLED_BY_USER",
                        "Cancellation was requested before artifact publication.", workerReference);
                continue;
            }
            WorkforceExportDtos.ArtifactEvidence artifact = null;
            try {
                artifact = artifactWriter.write(current);
                if (!artifact.artifactExpiresAt().isAfter(Instant.now())
                        || artifact.artifactExpiresAt().isAfter(
                                Instant.now().plus(Duration.ofHours(policy.artifactTtlHours())))) {
                    throw new IllegalStateException(
                            "The artifact expiry exceeds the governed retention window.");
                }
                WorkforceExportRepository.RequestRow latest = repository
                        .findForWorker(current.tenantId(), current.requestId())
                        .orElse(current);
                if ("CANCEL_REQUESTED".equals(latest.lifecycleState())) {
                    discard(artifact, latest.requestId());
                    artifact = null;
                    service.failWorkerAttempt(latest, "CANCELLED", null, "CANCELLED_BY_USER",
                            "Cancellation was requested before artifact publication.", workerReference);
                    continue;
                }
                service.completeWorkerAttempt(latest, artifact, workerReference);
                artifact = null;
            } catch (RuntimeException exception) {
                if (artifact != null) discard(artifact, current.requestId());
                WorkforceExportRepository.RequestRow latest = repository
                        .findForWorker(current.tenantId(), current.requestId())
                        .orElse(current);
                if (TERMINAL_STATES.contains(latest.lifecycleState())) {
                    log.warn("Workforce export request {} reached terminal state {} during recovery",
                            latest.requestId(), latest.lifecycleState());
                    continue;
                }
                String target = WorkforceExportLifecycle.failureTarget(
                        latest.lifecycleState(), latest.retryCycleAttemptCount(),
                        policy.maximumAttempts());
                Instant retryAt = "RETRY_WAIT".equals(target)
                        ? Instant.now().plus(backoff(latest.attemptCount())) : null;
                service.failWorkerAttempt(latest, target, retryAt, "ARTIFACT_WRITE_FAILED",
                        redacted(exception), workerReference);
                log.warn("Workforce export request {} failed on attempt {}",
                        latest.requestId(), latest.attemptCount());
            }
        }
    }

    private Duration backoff(int attemptCount) {
        long seconds = Math.min(900L, 1L << Math.min(9, Math.max(1, attemptCount)));
        return Duration.ofSeconds(seconds);
    }

    private String redacted(RuntimeException exception) {
        String name = exception.getClass().getSimpleName();
        return "Artifact writer failed (" + name + "). Review secured worker logs.";
    }

    private void discard(
            WorkforceExportDtos.ArtifactEvidence artifact,
            java.util.UUID requestId) {
        try {
            artifactWriter.discard(artifact);
        } catch (RuntimeException cleanupFailure) {
            log.error("Workforce export staging artifact cleanup failed for request {}",
                    requestId, cleanupFailure);
        }
    }
}
