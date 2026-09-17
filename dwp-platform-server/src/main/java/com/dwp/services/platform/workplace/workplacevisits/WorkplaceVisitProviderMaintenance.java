package com.dwp.services.platform.workplace.workplacevisits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitProviderPort.*;

/** Recovers abandoned leases before dispatching a bounded, tenant-agnostic queue batch. */
@Component
class WorkplaceVisitProviderMaintenance {
    private static final Logger log =
            LoggerFactory.getLogger(WorkplaceVisitProviderMaintenance.class);

    private final WorkplaceVisitRepository repository;
    private final WorkplaceVisitProviderResultService results;
    private final WorkplaceVisitProviderWorker worker;
    private final boolean enabled;
    private final int batchSize;
    private final Duration processingTimeout;
    private final Clock clock;

    @Autowired
    WorkplaceVisitProviderMaintenance(
            WorkplaceVisitRepository repository,
            WorkplaceVisitProviderResultService results,
            WorkplaceVisitProviderWorker worker,
            @Value("${dwp.workplace.visits.provider-worker.enabled:true}") boolean enabled,
            @Value("${dwp.workplace.visits.provider-worker.batch-size:50}") int batchSize,
            @Value("${dwp.workplace.visits.provider-worker.processing-timeout:PT2M}")
            Duration processingTimeout) {
        this(repository, results, worker, enabled, batchSize, processingTimeout,
                Clock.systemUTC());
    }

    WorkplaceVisitProviderMaintenance(
            WorkplaceVisitRepository repository,
            WorkplaceVisitProviderResultService results,
            WorkplaceVisitProviderWorker worker,
            boolean enabled,
            int batchSize,
            Duration processingTimeout,
            Clock clock) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        if (processingTimeout == null || processingTimeout.compareTo(Duration.ofSeconds(10)) < 0
                || processingTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "processingTimeout must be between 10 seconds and 1 hour");
        }
        this.repository = repository;
        this.results = results;
        this.worker = worker;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.processingTimeout = processingTimeout;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.visits.provider-worker.poll-delay-ms:2000}")
    void scheduledRun() {
        if (!enabled) return;
        recoverStale();
        worker.processPending(batchSize);
    }

    int recoverStale() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime staleBefore = now.minus(processingTimeout);
        int recovered = 0;
        for (var row : repository.staleProcessing(staleBefore, batchSize)) {
            try {
                boolean changed;
                if ("CHECK_PROVIDER_STATUS".equals(row.operationType())) {
                    changed = repository.retryStaleLookup(row.tenantId(), row.id(),
                            staleBefore, now, now);
                } else {
                    results.apply(row.tenantId(), row.id(), new ProviderOutcome(
                            OutcomeState.RESULT_UNKNOWN, "operation:" + row.id(),
                            "PROVIDER_PROCESS_RESTARTED"));
                    changed = true;
                }
                if (changed) recovered++;
            } catch (RuntimeException racedOrUnavailable) {
                log.warn("Visit provider recovery did not complete for outboxId={}",
                        row.id(), racedOrUnavailable);
            }
        }
        return recovered;
    }
}
