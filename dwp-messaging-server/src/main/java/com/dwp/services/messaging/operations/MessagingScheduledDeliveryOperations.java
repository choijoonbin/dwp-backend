package com.dwp.services.messaging.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

/** Database-backed, cross-instance evidence for the durable scheduled delivery worker. */
@Component
public final class MessagingScheduledDeliveryOperations {

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Duration heartbeatMaximumAge;
    private final Duration overdueMaximumAge;
    private final Clock clock;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong overdueGauge = new AtomicLong();
    private final AtomicLong failedGauge = new AtomicLong();

    @Autowired
    public MessagingScheduledDeliveryOperations(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            @Value("${dwp.messaging.scheduled-delivery.enabled:true}") boolean enabled,
            @Value("${dwp.messaging.scheduled-delivery.heartbeat-maximum-age:PT2M}")
            Duration heartbeatMaximumAge,
            @Value("${dwp.messaging.scheduled-delivery.overdue-maximum-age:PT2M}")
            Duration overdueMaximumAge) {
        this(jdbc, meters, enabled, heartbeatMaximumAge, overdueMaximumAge, Clock.systemUTC());
    }

    MessagingScheduledDeliveryOperations(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            boolean enabled,
            Duration heartbeatMaximumAge,
            Duration overdueMaximumAge,
            Clock clock) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.heartbeatMaximumAge = requirePositive(heartbeatMaximumAge, "heartbeat maximum age");
        this.overdueMaximumAge = requirePositive(overdueMaximumAge, "overdue maximum age");
        this.clock = clock;
        Gauge.builder("dwp.messaging.scheduled.queue.pending", pendingGauge, AtomicLong::get)
                .description("Pending scheduled delivery commands")
                .register(meters);
        Gauge.builder("dwp.messaging.scheduled.queue.overdue", overdueGauge, AtomicLong::get)
                .description("Scheduled delivery commands currently overdue")
                .register(meters);
        Gauge.builder("dwp.messaging.scheduled.failed.recent", failedGauge, AtomicLong::get)
                .description("Terminal scheduled delivery failures in the last 24 hours")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${dwp.messaging.scheduled-delivery.health-initial-delay:PT10S}",
            fixedDelayString = "${dwp.messaging.scheduled-delivery.health-interval:PT1M}")
    public void refreshMetrics() {
        status();
    }

    public ScheduledDeliveryStatus status() {
        return status(null, true);
    }

    public ScheduledDeliveryStatus status(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive.");
        return status(tenantId, false);
    }

    private ScheduledDeliveryStatus status(Long tenantId, boolean updateGlobalGauges) {
        if (!enabled) return ScheduledDeliveryStatus.disabled();
        try {
            ScheduledDeliveryEvidence evidence = jdbc.queryForObject("""
                    WITH queue AS (
                        SELECT COUNT(*) FILTER (
                                   WHERE status IN ('PENDING', 'PROCESSING')) AS pending_count,
                               COUNT(*) FILTER (
                                   WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                                      OR (status = 'PROCESSING'
                                          AND processing_lease_until < CURRENT_TIMESTAMP))
                                   AS overdue_count,
                               MIN(CASE
                                   WHEN status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
                                       THEN next_attempt_at
                                   WHEN status = 'PROCESSING'
                                        AND processing_lease_until < CURRENT_TIMESTAMP
                                       THEN processing_lease_until
                               END) AS oldest_overdue_at,
                               COUNT(*) FILTER (
                                   WHERE status = 'FAILED'
                                     AND updated_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours')
                                   AS recent_failed_count
                          FROM msg_scheduled_messages
                         WHERE (?::BIGINT IS NULL OR tenant_id = ?)
                    ), worker AS (
                        SELECT MAX(last_completed_at) AS last_completed_at,
                               MAX(last_failure_at) AS last_failure_at
                          FROM msg_scheduled_delivery_worker_heartbeats
                    ), latest_failure AS (
                        SELECT last_failure_code
                          FROM msg_scheduled_delivery_worker_heartbeats
                         WHERE last_failure_at IS NOT NULL
                         ORDER BY last_failure_at DESC, worker_id
                         LIMIT 1
                    )
                    SELECT queue.pending_count, queue.overdue_count,
                           queue.oldest_overdue_at, queue.recent_failed_count,
                           worker.last_completed_at, worker.last_failure_at,
                           latest_failure.last_failure_code
                      FROM queue CROSS JOIN worker
                      LEFT JOIN latest_failure ON TRUE
                    """, (row, ignored) -> new ScheduledDeliveryEvidence(
                    row.getLong("pending_count"),
                    row.getLong("overdue_count"),
                    row.getObject("oldest_overdue_at", OffsetDateTime.class),
                    row.getLong("recent_failed_count"),
                    row.getObject("last_completed_at", OffsetDateTime.class),
                    row.getObject("last_failure_at", OffsetDateTime.class),
                    row.getString("last_failure_code")), tenantId, tenantId);
            ScheduledDeliveryStatus status = evaluate(evidence);
            if (updateGlobalGauges) {
                pendingGauge.set(status.pendingCount());
                overdueGauge.set(status.overdueCount());
                failedGauge.set(status.recentFailedCount());
            }
            return status;
        } catch (RuntimeException exception) {
            return new ScheduledDeliveryStatus(
                    true, false, "BLOCKED", "SCHEDULED_DELIVERY_EVIDENCE_QUERY_FAILED",
                    null, null, 0, 0, 0, null);
        }
    }

    private ScheduledDeliveryStatus evaluate(ScheduledDeliveryEvidence evidence) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String state = "READY";
        String reason = null;
        boolean effective = true;
        if (evidence.lastFailureAt() != null
                && (evidence.lastCompletedAt() == null
                    || evidence.lastFailureAt().isAfter(evidence.lastCompletedAt()))) {
            state = "BLOCKED";
            reason = value(evidence.lastFailureCode(), "SCHEDULED_DELIVERY_WORKER_FAILED");
            effective = false;
        } else if (evidence.lastCompletedAt() == null) {
            state = "BLOCKED";
            reason = "SCHEDULED_DELIVERY_NOT_YET_VERIFIED";
            effective = false;
        } else if (Duration.between(evidence.lastCompletedAt(), now)
                .compareTo(heartbeatMaximumAge) > 0) {
            state = "BLOCKED";
            reason = "SCHEDULED_DELIVERY_HEARTBEAT_STALE";
            effective = false;
        } else if (evidence.oldestOverdueAt() != null
                && Duration.between(evidence.oldestOverdueAt(), now)
                .compareTo(overdueMaximumAge) > 0) {
            state = "BLOCKED";
            reason = "SCHEDULED_DELIVERY_BACKLOG_OVERDUE";
            effective = false;
        } else if (evidence.recentFailedCount() > 0) {
            state = "DEGRADED";
            reason = "SCHEDULED_DELIVERY_FAILURES_REQUIRE_REVIEW";
        }
        return new ScheduledDeliveryStatus(
                true, effective, state, reason, evidence.lastCompletedAt(),
                evidence.lastFailureAt(), evidence.pendingCount(), evidence.overdueCount(),
                evidence.recentFailedCount(), evidence.oldestOverdueAt());
    }

    private Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Scheduled delivery " + label + " must be positive.");
        }
        return value;
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private record ScheduledDeliveryEvidence(
            long pendingCount,
            long overdueCount,
            OffsetDateTime oldestOverdueAt,
            long recentFailedCount,
            OffsetDateTime lastCompletedAt,
            OffsetDateTime lastFailureAt,
            String lastFailureCode) { }

    public record ScheduledDeliveryStatus(
            boolean configured,
            boolean effective,
            String state,
            String reasonCode,
            OffsetDateTime lastCompletedAt,
            OffsetDateTime lastFailureAt,
            long pendingCount,
            long overdueCount,
            long recentFailedCount,
            OffsetDateTime oldestOverdueAt) {

        private static ScheduledDeliveryStatus disabled() {
            return new ScheduledDeliveryStatus(
                    false, false, "DISABLED", "SCHEDULED_DELIVERY_DISABLED",
                    null, null, 0, 0, 0, null);
        }
    }
}
