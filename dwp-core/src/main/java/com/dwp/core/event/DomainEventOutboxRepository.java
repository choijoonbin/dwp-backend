package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable producer ledger with aggregate ordering, leases, retries, and DLQ replay. */
public class DomainEventOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DomainEventOutboxRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean append(DomainEventEnvelope event, String payload, String payloadHash) {
        int changed = jdbc.update("""
                INSERT INTO sys_domain_event_outbox (
                    outbox_id, event_id, spec_version, event_source, event_type,
                    schema_version, subject, tenant_id, aggregate_type, aggregate_id,
                    aggregate_sequence, correlation_id, causation_id, trace_parent,
                    payload, payload_sha256, status, attempt_count, available_at,
                    created_at, updated_at)
                VALUES (
                    :outboxId, :eventId, :specVersion, :eventSource, :eventType,
                    :schemaVersion, :subject, :tenantId, :aggregateType, :aggregateId,
                    :aggregateSequence, :correlationId, :causationId, :traceParent,
                    CAST(:payload AS jsonb), :payloadHash, 'PENDING', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (event_id) DO NOTHING
                """, eventParameters(event, payload, payloadHash)
                .addValue("outboxId", UUID.randomUUID()));
        return changed == 1;
    }

    public Optional<String> payloadHash(UUID eventId) {
        return jdbc.queryForList("""
                SELECT payload_sha256
                  FROM sys_domain_event_outbox
                 WHERE event_id = :eventId
                """, new MapSqlParameterSource("eventId", eventId), String.class)
                .stream().findFirst();
    }

    public List<ClaimedEvent> claim(String workerId, int batchSize, int leaseSeconds) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT candidate.outbox_id
                      FROM sys_domain_event_outbox candidate
                     WHERE candidate.status IN ('PENDING', 'FAILED')
                       AND candidate.available_at <= CURRENT_TIMESTAMP
                       AND (candidate.locked_until IS NULL
                            OR candidate.locked_until < CURRENT_TIMESTAMP)
                       AND NOT EXISTS (
                           SELECT 1
                             FROM sys_domain_event_outbox predecessor
                            WHERE predecessor.event_source = candidate.event_source
                              AND predecessor.aggregate_type = candidate.aggregate_type
                              AND predecessor.aggregate_id = candidate.aggregate_id
                              AND predecessor.aggregate_sequence < candidate.aggregate_sequence
                              AND predecessor.status <> 'PUBLISHED')
                     ORDER BY candidate.created_at, candidate.outbox_id
                     LIMIT :batchSize
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE sys_domain_event_outbox outbox
                   SET status = 'SENDING',
                       attempt_count = outbox.attempt_count + 1,
                       locked_by = :workerId,
                       locked_until = CURRENT_TIMESTAMP + make_interval(secs => :leaseSeconds),
                       updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE outbox.outbox_id = candidates.outbox_id
                RETURNING outbox.outbox_id, outbox.payload::text, outbox.attempt_count
                """, new MapSqlParameterSource()
                        .addValue("workerId", workerId)
                        .addValue("batchSize", batchSize)
                        .addValue("leaseSeconds", leaseSeconds),
                (result, ignored) -> new ClaimedEvent(
                        result.getObject("outbox_id", UUID.class),
                        DomainEventJson.deserialize(objectMapper, result.getString("payload")),
                        result.getInt("attempt_count")));
    }

    public void markPublished(List<UUID> outboxIds) {
        if (outboxIds.isEmpty()) return;
        jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL, last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id IN (:ids)
                """, new MapSqlParameterSource("ids", outboxIds));
    }

    public void markFailed(
            UUID outboxId,
            int attempts,
            int maximumAttempts,
            String error) {
        int delaySeconds = Math.min(300, Math.max(2, 1 << Math.min(8, attempts)));
        jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status = CASE
                           WHEN :attempts >= :maximumAttempts THEN 'DEAD'
                           ELSE 'FAILED'
                       END,
                       available_at = CURRENT_TIMESTAMP
                           + make_interval(secs => :delaySeconds),
                       dead_lettered_at = CASE
                           WHEN :attempts >= :maximumAttempts THEN CURRENT_TIMESTAMP
                           ELSE NULL
                       END,
                       locked_by = NULL, locked_until = NULL,
                       last_error = :error, updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = :outboxId
                """, new MapSqlParameterSource()
                        .addValue("attempts", attempts)
                        .addValue("maximumAttempts", maximumAttempts)
                        .addValue("delaySeconds", delaySeconds)
                        .addValue("error", truncate(error, 1000))
                        .addValue("outboxId", outboxId));
    }

    public boolean replayDead(
            UUID eventId,
            String actor,
            String reason) {
        int changed = jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status = 'PENDING', attempt_count = 0,
                       available_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL,
                       dead_lettered_at = NULL, last_error = NULL,
                       replay_count = replay_count + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE event_id = :eventId
                   AND status = 'DEAD'
                """, new MapSqlParameterSource("eventId", eventId));
        if (changed == 1) {
            auditReplay("OUTBOX", eventId.toString(), null, actor, reason);
        }
        return changed == 1;
    }

    public int releaseExpiredLeases(Instant now) {
        return jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status = 'FAILED', locked_by = NULL, locked_until = NULL,
                       available_at = :now, last_error = 'Publisher lease expired',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE status = 'SENDING' AND locked_until < :now
                """, new MapSqlParameterSource("now", now));
    }

    private void auditReplay(
            String direction,
            String targetId,
            String consumerName,
            String actor,
            String reason) {
        jdbc.update("""
                INSERT INTO sys_domain_event_replay_audit (
                    replay_request_id, direction, target_id, consumer_name,
                    requested_by, reason, requested_at)
                VALUES (
                    :requestId, :direction, :targetId, :consumerName,
                    :actor, :reason, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                        .addValue("requestId", UUID.randomUUID())
                        .addValue("direction", direction)
                        .addValue("targetId", targetId)
                        .addValue("consumerName", consumerName)
                        .addValue("actor", actor)
                        .addValue("reason", reason));
    }

    private MapSqlParameterSource eventParameters(
            DomainEventEnvelope event,
            String payload,
            String payloadHash) {
        return new MapSqlParameterSource()
                .addValue("eventId", event.id())
                .addValue("specVersion", event.specVersion())
                .addValue("eventSource", event.source())
                .addValue("eventType", event.type())
                .addValue("schemaVersion", event.schemaVersion())
                .addValue("subject", event.subject())
                .addValue("tenantId", event.tenantId())
                .addValue("aggregateType", event.aggregateType())
                .addValue("aggregateId", event.aggregateId())
                .addValue("aggregateSequence", event.aggregateSequence())
                .addValue("correlationId", event.correlationId())
                .addValue("causationId", event.causationId())
                .addValue("traceParent", event.traceParent())
                .addValue("payload", payload)
                .addValue("payloadHash", payloadHash);
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isBlank()) return "Unknown domain-event delivery failure";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public record ClaimedEvent(UUID outboxId, DomainEventEnvelope event, int attempts) {
    }
}
