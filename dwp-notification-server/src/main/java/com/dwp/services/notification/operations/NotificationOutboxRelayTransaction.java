package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationOutboxRepository.OutboxEvent;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationOutboxRelayTransaction {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationOutboxRepository repository;

    public NotificationOutboxRelayTransaction(
            NotificationDatabaseScope databaseScope,
            NotificationOutboxRepository repository) {
        this.databaseScope = databaseScope;
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> lease(
            long tenantId,
            String leaseToken,
            Instant now,
            Instant leaseUntil,
            int batchSize) {
        databaseScope.applyWorker(tenantId);
        return repository.lease(tenantId, leaseToken, now, leaseUntil, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(
            long tenantId,
            UUID outboxId,
            String leaseToken,
            Instant publishedAt) {
        databaseScope.applyWorker(tenantId);
        return repository.markPublished(tenantId, outboxId, leaseToken, publishedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            long tenantId,
            UUID outboxId,
            String leaseToken,
            int attemptCount,
            int maximumAttempts,
            Instant nextAttemptAt,
            String error) {
        databaseScope.applyWorker(tenantId);
        return repository.markFailed(
                tenantId,
                outboxId,
                leaseToken,
                attemptCount,
                maximumAttempts,
                nextAttemptAt,
                error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupPublished(long tenantId, Instant cutoff, int batchSize) {
        databaseScope.applyWorker(tenantId);
        return repository.cleanupPublished(tenantId, cutoff, batchSize);
    }
}
