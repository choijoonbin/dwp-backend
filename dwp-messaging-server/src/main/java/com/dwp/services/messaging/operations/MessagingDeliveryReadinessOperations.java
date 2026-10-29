package com.dwp.services.messaging.operations;

import com.dwp.core.event.DomainEventOutboxRelay;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/** Tenant-scoped evidence for the durable realtime log and notification producer outbox. */
@Component
public final class MessagingDeliveryReadinessOperations {

    private static final String MESSAGING_EVENT_SOURCE = "urn:dwp:messaging";

    private final JdbcTemplate jdbc;
    private final DomainEventOutboxRelay outboxRelay;
    private final boolean notificationTransportEnabled;
    private final Duration outboxBacklogMaximumAge;
    private final int outboxBacklogWarningThreshold;
    private final Clock clock;

    @Autowired
    public MessagingDeliveryReadinessOperations(
            JdbcTemplate jdbc,
            DomainEventOutboxRelay outboxRelay,
            @Value("${dwp.events.transport-enabled:false}") boolean notificationTransportEnabled,
            @Value("${dwp.messaging.readiness.notification-outbox-maximum-age:PT5M}")
            Duration outboxBacklogMaximumAge,
            @Value("${dwp.messaging.readiness.notification-outbox-warning-threshold:1000}")
            int outboxBacklogWarningThreshold) {
        this(
                jdbc, outboxRelay, notificationTransportEnabled, outboxBacklogMaximumAge,
                outboxBacklogWarningThreshold, Clock.systemUTC());
    }

    MessagingDeliveryReadinessOperations(
            JdbcTemplate jdbc,
            DomainEventOutboxRelay outboxRelay,
            boolean notificationTransportEnabled,
            Duration outboxBacklogMaximumAge,
            int outboxBacklogWarningThreshold,
            Clock clock) {
        if (outboxBacklogMaximumAge == null
                || outboxBacklogMaximumAge.compareTo(Duration.ofSeconds(30)) < 0
                || outboxBacklogMaximumAge.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "Messaging notification outbox maximum age must be 30 seconds to 24 hours.");
        }
        if (outboxBacklogWarningThreshold < 1 || outboxBacklogWarningThreshold > 1_000_000) {
            throw new IllegalArgumentException(
                    "Messaging notification outbox warning threshold must be 1 to 1000000.");
        }
        this.jdbc = jdbc;
        this.outboxRelay = outboxRelay;
        this.notificationTransportEnabled = notificationTransportEnabled;
        this.outboxBacklogMaximumAge = outboxBacklogMaximumAge;
        this.outboxBacklogWarningThreshold = outboxBacklogWarningThreshold;
        this.clock = clock;
    }

    public DeliveryEvidence evidence(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("Messaging tenant must be positive.");
        return new DeliveryEvidence(durableLog(tenantId), notificationOutbox(tenantId));
    }

    private DurableLogStatus durableLog(long tenantId) {
        OffsetDateTime verifiedAt = OffsetDateTime.now(clock);
        try {
            jdbc.queryForObject(
                    "SELECT COALESCE(MAX(event_sequence), 0)"
                            + " FROM msg_realtime_events WHERE tenant_id = ?",
                    Long.class, tenantId);
            return new DurableLogStatus(true, "READY", null, verifiedAt);
        } catch (RuntimeException exception) {
            return new DurableLogStatus(
                    false, "BLOCKED", "DURABLE_EVENT_LOG_UNAVAILABLE", verifiedAt);
        }
    }

    private NotificationOutboxStatus notificationOutbox(long tenantId) {
        OffsetDateTime verifiedAt = OffsetDateTime.now(clock);
        if (!notificationTransportEnabled) {
            return new NotificationOutboxStatus(
                    false, false, "DISABLED", "NOTIFICATION_TRANSPORT_DISABLED",
                    verifiedAt, 0);
        }
        OutboxEvidence evidence;
        try {
            evidence = jdbc.queryForObject("""
                    SELECT COUNT(*) FILTER (
                               WHERE status IN ('PENDING', 'SENDING', 'FAILED')) AS active_count,
                           COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_count,
                           COUNT(*) FILTER (WHERE status = 'DEAD') AS dead_count,
                           MIN(created_at) FILTER (
                               WHERE status IN ('PENDING', 'SENDING', 'FAILED')) AS oldest_active_at
                      FROM sys_domain_event_outbox
                     WHERE tenant_id = ?
                       AND event_source = ?
                    """, (row, ignored) -> new OutboxEvidence(
                            row.getLong("active_count"),
                            row.getLong("failed_count"),
                            row.getLong("dead_count"),
                            row.getObject("oldest_active_at", OffsetDateTime.class)),
                    tenantId, MESSAGING_EVENT_SOURCE);
        } catch (RuntimeException exception) {
            return new NotificationOutboxStatus(
                    true, false, "BLOCKED", "NOTIFICATION_OUTBOX_EVIDENCE_UNAVAILABLE",
                    verifiedAt, 0);
        }
        if (evidence == null) {
            return new NotificationOutboxStatus(
                    true, false, "BLOCKED", "NOTIFICATION_OUTBOX_EVIDENCE_UNAVAILABLE",
                    verifiedAt, 0);
        }
        return classifyOutbox(evidence, verifiedAt);
    }

    NotificationOutboxStatus classifyOutbox(
            OutboxEvidence evidence, OffsetDateTime verifiedAt) {
        long pendingWork = evidence.activeCount() + evidence.deadCount();
        if (!outboxRelay.isRunning()) {
            return new NotificationOutboxStatus(
                    true, false, "BLOCKED", "NOTIFICATION_OUTBOX_RELAY_NOT_RUNNING",
                    verifiedAt, pendingWork);
        }
        if (evidence.deadCount() > 0) {
            return new NotificationOutboxStatus(
                    true, false, "BLOCKED", "NOTIFICATION_OUTBOX_DEAD_LETTERS",
                    verifiedAt, pendingWork);
        }
        if (evidence.oldestActiveAt() != null
                && Duration.between(evidence.oldestActiveAt(), verifiedAt)
                .compareTo(outboxBacklogMaximumAge) > 0) {
            return new NotificationOutboxStatus(
                    true, false, "BLOCKED", "NOTIFICATION_OUTBOX_BACKLOG_STALE",
                    verifiedAt, pendingWork);
        }
        if (evidence.failedCount() > 0) {
            return new NotificationOutboxStatus(
                    true, true, "DEGRADED", "NOTIFICATION_OUTBOX_RETRYING",
                    verifiedAt, pendingWork);
        }
        if (evidence.activeCount() >= outboxBacklogWarningThreshold) {
            return new NotificationOutboxStatus(
                    true, true, "DEGRADED", "NOTIFICATION_OUTBOX_BACKLOG_HIGH",
                    verifiedAt, pendingWork);
        }
        return new NotificationOutboxStatus(
                true, true, "READY", null, verifiedAt, pendingWork);
    }

    public record DeliveryEvidence(
            DurableLogStatus durableLog,
            NotificationOutboxStatus notificationOutbox) {
    }

    public record DurableLogStatus(
            boolean effective,
            String state,
            String reasonCode,
            OffsetDateTime verifiedAt) {
    }

    public record NotificationOutboxStatus(
            boolean configured,
            boolean effective,
            String state,
            String reasonCode,
            OffsetDateTime verifiedAt,
            long pendingWorkCount) {
    }

    record OutboxEvidence(
            long activeCount,
            long failedCount,
            long deadCount,
            OffsetDateTime oldestActiveAt) {
    }
}
