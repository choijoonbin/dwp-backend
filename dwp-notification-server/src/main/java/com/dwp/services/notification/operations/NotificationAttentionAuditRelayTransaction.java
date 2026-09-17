package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationAttentionAuditOutboxRepository.AttentionAuditEvent;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationAttentionAuditRelayTransaction {

    static final String RELAY_ROLE = "dwp_notification_attention_audit_relay";

    private final NotificationDatabaseScope databaseScope;
    private final NotificationAttentionAuditOutboxRepository repository;
    private final JdbcTemplate jdbc;

    public NotificationAttentionAuditRelayTransaction(
            NotificationDatabaseScope databaseScope,
            NotificationAttentionAuditOutboxRepository repository,
            JdbcTemplate jdbc) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AttentionAuditEvent> lease(
            long tenantId,
            String owner,
            Instant now,
            Instant leaseUntil,
            int batchSize) {
        applyRelayScope(tenantId);
        return repository.lease(tenantId, owner, now, leaseUntil, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(
            long tenantId,
            UUID eventId,
            String owner,
            Instant publishedAt) {
        applyRelayScope(tenantId);
        return repository.markPublished(tenantId, eventId, owner, publishedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            long tenantId,
            UUID eventId,
            String owner,
            int attemptCount,
            int maximumAttempts,
            Instant nextAttemptAt,
            String error) {
        applyRelayScope(tenantId);
        return repository.markFailed(
                tenantId, eventId, owner, attemptCount, maximumAttempts,
                nextAttemptAt, error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupPublished(long tenantId, Instant cutoff, int batchSize) {
        applyRelayScope(tenantId);
        return repository.cleanupPublished(tenantId, cutoff, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long deadCount(long tenantId) {
        applyRelayScope(tenantId);
        return repository.deadCount(tenantId);
    }

    private void applyRelayScope(long tenantId) {
        databaseScope.applyWorker(tenantId);
        jdbc.execute("SET LOCAL ROLE " + RELAY_ROLE);
    }
}
