package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationCounterReconciliationRepository.CounterRow;
import com.dwp.services.notification.operations.NotificationCounterReconciliationRepository.ProjectionCounterRow;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class NotificationCounterReconciliationService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationCounterReconciliationRepository repository;
    private final int batchSize;
    private final Counter detectedDrift;
    private final Counter repairedDrift;

    public NotificationCounterReconciliationService(
            NotificationDatabaseScope databaseScope,
            NotificationCounterReconciliationRepository repository,
            MeterRegistry meterRegistry,
            @Value("${dwp.notification.reconciliation.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(
                    "Notification reconciliation batch size must be between 1 and 500.");
        }
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.batchSize = batchSize;
        this.detectedDrift = Counter.builder("dwp.notification.counter.drift.detected")
                .description("User notification counters found out of sync")
                .register(meterRegistry);
        this.repairedDrift = Counter.builder("dwp.notification.counter.drift.repaired")
                .description("User notification counters repaired from durable projections")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationResult reconcileTenant(long tenantId, Instant now) {
        databaseScope.applyWorker(tenantId);
        List<Long> candidates = repository.driftedUserIds(tenantId, now, batchSize);
        detectedDrift.increment(candidates.size());
        int repaired = 0;
        for (Long userId : candidates) {
            List<ProjectionCounterRow> projections = repository.lockProjectionRows(tenantId, userId);
            repository.ensureCounter(tenantId, userId);
            CounterRow current = repository.lockCounter(tenantId, userId);
            CounterExpectation expectation = expectation(projections, now);
            if (!expectation.counter().sameCounts(current)) {
                repository.repair(
                        tenantId,
                        userId,
                        expectation.counter(),
                        Math.max(current.version(), expectation.maximumProjectionVersion()));
                repaired++;
            }
        }
        repairedDrift.increment(repaired);
        return new ReconciliationResult(candidates.size(), repaired, candidates.size() == batchSize);
    }

    static CounterExpectation expectation(List<ProjectionCounterRow> projections, Instant now) {
        long unread = 0;
        long actionable = 0;
        long urgent = 0;
        long maximumVersion = 0;
        for (ProjectionCounterRow row : projections) {
            maximumVersion = Math.max(maximumVersion, row.changeVersion());
            boolean visibleUnread = "ACTIVE".equals(row.inboxState())
                    && row.readAt() == null
                    && (row.snoozedUntil() == null || !row.snoozedUntil().isAfter(now));
            if (!visibleUnread) continue;
            unread++;
            if (row.actionRequired()) actionable++;
            if ("URGENT".equals(row.priority())) urgent++;
        }
        return new CounterExpectation(
                new CounterRow(unread, actionable, urgent, maximumVersion),
                maximumVersion);
    }

    public record ReconciliationResult(int detected, int repaired, boolean moreLikely) {
    }

    record CounterExpectation(CounterRow counter, long maximumProjectionVersion) {
    }
}
