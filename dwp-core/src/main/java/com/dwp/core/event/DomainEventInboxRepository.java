package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Transactional consumer ledger for payload integrity, dedupe, ordering, DLQ, and replay. */
public class DomainEventInboxRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DomainEventInboxRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public BeginResult begin(
            String consumerName,
            DomainEventEnvelope event,
            String workerId,
            Duration lease) {
        String payload = DomainEventJson.serialize(objectMapper, event);
        String payloadHash = DomainEventJson.sha256(payload);
        Instant now = Instant.now();
        int inserted = jdbc.update("""
                INSERT INTO sys_domain_event_inbox (
                    consumer_name, event_id, event_source, event_type, schema_version,
                    tenant_id, aggregate_type, aggregate_id, aggregate_sequence,
                    correlation_id, causation_id, trace_parent, payload, payload_sha256,
                    status, attempt_count, available_at, created_at, updated_at)
                VALUES (
                    :consumerName, :eventId, :eventSource, :eventType, :schemaVersion,
                    :tenantId, :aggregateType, :aggregateId, :aggregateSequence,
                    :correlationId, :causationId, :traceParent, CAST(:payload AS jsonb),
                    :payloadHash, 'RECEIVED', 0, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, eventParameters(consumerName, event, payload, payloadHash));

        InboxRow row = lockInbox(consumerName, event.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Domain-event inbox insert could not be read back."));
        if (!payloadHash.equals(row.payloadHash())) {
            markPermanentFailure(
                    consumerName, event.id(),
                    "Event id was reused with a different payload hash.");
            return new BeginResult(BeginState.PAYLOAD_CONFLICT, null, row.attemptCount());
        }
        if (inserted == 0) {
            if ("SUCCEEDED".equals(row.status()) || "DUPLICATE".equals(row.status())) {
                return new BeginResult(BeginState.DUPLICATE, null, row.attemptCount());
            }
            if ("DEAD".equals(row.status())) {
                return new BeginResult(BeginState.DEAD, null, row.attemptCount());
            }
            if ("PROCESSING".equals(row.status())
                    && row.lockedUntil() != null
                    && row.lockedUntil().isAfter(now)) {
                return new BeginResult(BeginState.BUSY, null, row.attemptCount());
            }
            if (row.availableAt() != null && row.availableAt().isAfter(now)) {
                return new BeginResult(BeginState.DEFERRED, null, row.attemptCount());
            }
        }

        jdbc.update("""
                INSERT INTO sys_domain_event_offsets (
                    consumer_name, tenant_id, event_source, aggregate_type, aggregate_id,
                    last_sequence, updated_at)
                VALUES (
                    :consumerName, :tenantScope, :eventSource, :aggregateType, :aggregateId,
                    0, CURRENT_TIMESTAMP)
                ON CONFLICT (
                    consumer_name, tenant_id, event_source, aggregate_type, aggregate_id)
                DO NOTHING
                """, offsetParameters(consumerName, event));
        long lastSequence = jdbc.queryForObject("""
                SELECT last_sequence
                  FROM sys_domain_event_offsets
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantScope
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                 FOR UPDATE
                """, offsetParameters(consumerName, event), Long.class);
        DomainEventOrderingPolicy.Decision ordering =
                DomainEventOrderingPolicy.decide(lastSequence, event.aggregateSequence());
        if (ordering == DomainEventOrderingPolicy.Decision.DUPLICATE) {
            markDuplicate(consumerName, event.id(), lastSequence);
            return new BeginResult(BeginState.DUPLICATE, null, row.attemptCount());
        }
        if (ordering == DomainEventOrderingPolicy.Decision.OUT_OF_ORDER) {
            deferOutOfOrder(consumerName, event.id(), lastSequence + 1);
            return new BeginResult(BeginState.OUT_OF_ORDER, null, row.attemptCount());
        }

        String lockToken = UUID.randomUUID().toString();
        int attempt = row.attemptCount() + 1;
        int changed = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'PROCESSING', attempt_count = :attempt,
                       locked_by = :workerId, lock_token = :lockToken,
                       locked_until = :lockedUntil, last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                """, new MapSqlParameterSource()
                        .addValue("attempt", attempt)
                        .addValue("workerId", workerId)
                        .addValue("lockToken", lockToken)
                        .addValue("lockedUntil", now.plus(lease))
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", event.id()));
        if (changed != 1) {
            throw new IllegalStateException("Domain-event inbox lease could not be acquired.");
        }
        return new BeginResult(BeginState.ACQUIRED, lockToken, attempt);
    }

    public void complete(
            String consumerName,
            DomainEventEnvelope event,
            String lockToken) {
        int inboxChanged = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'SUCCEEDED', processed_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, lock_token = NULL, locked_until = NULL,
                       last_error = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                   AND status = 'PROCESSING'
                   AND lock_token = :lockToken
                """, new MapSqlParameterSource()
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", event.id())
                        .addValue("lockToken", lockToken));
        if (inboxChanged != 1) {
            throw new IllegalStateException("Domain-event inbox completion lost its lease.");
        }
        int offsetChanged = jdbc.update("""
                UPDATE sys_domain_event_offsets
                   SET last_sequence = :aggregateSequence,
                       last_event_id = :eventId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantScope
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND last_sequence = :expectedPredecessor
                """, offsetParameters(consumerName, event)
                        .addValue("eventId", event.id())
                        .addValue("aggregateSequence", event.aggregateSequence())
                        .addValue("expectedPredecessor", event.aggregateSequence() - 1));
        if (offsetChanged != 1) {
            throw new IllegalStateException("Domain-event aggregate order changed during processing.");
        }
    }

    public FailureState fail(
            String consumerName,
            UUID eventId,
            String lockToken,
            int attempt,
            int maximumAttempts,
            String error) {
        FailureState state = attempt >= maximumAttempts
                ? FailureState.DEAD
                : FailureState.RETRYABLE;
        int delaySeconds = Math.min(300, Math.max(2, 1 << Math.min(8, attempt)));
        int changed = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = :status,
                       available_at = CURRENT_TIMESTAMP
                           + make_interval(secs => :delaySeconds),
                       dead_lettered_at = CASE
                           WHEN :status = 'DEAD' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       locked_by = NULL, lock_token = NULL, locked_until = NULL,
                       last_error = :error, updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                   AND status = 'PROCESSING'
                   AND lock_token = :lockToken
                """, new MapSqlParameterSource()
                        .addValue("status", state == FailureState.DEAD ? "DEAD" : "FAILED")
                        .addValue("delaySeconds", delaySeconds)
                        .addValue("error", truncate(error, 1000))
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", eventId)
                        .addValue("lockToken", lockToken));
        if (changed != 1) {
            throw new IllegalStateException("Domain-event failure outcome lost its lease.");
        }
        return state;
    }

    public void quarantine(
            String consumerName,
            DomainEventEnvelope event,
            String reason) {
        String payload = DomainEventJson.serialize(objectMapper, event);
        String payloadHash = DomainEventJson.sha256(payload);
        jdbc.update("""
                INSERT INTO sys_domain_event_inbox (
                    consumer_name, event_id, event_source, event_type, schema_version,
                    tenant_id, aggregate_type, aggregate_id, aggregate_sequence,
                    correlation_id, causation_id, trace_parent, payload, payload_sha256,
                    status, attempt_count, available_at, dead_lettered_at, last_error,
                    created_at, updated_at)
                VALUES (
                    :consumerName, :eventId, :eventSource, :eventType, :schemaVersion,
                    :tenantId, :aggregateType, :aggregateId, :aggregateSequence,
                    :correlationId, :causationId, :traceParent, CAST(:payload AS jsonb),
                    :payloadHash, 'DEAD', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    :reason, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (consumer_name, event_id) DO UPDATE
                   SET status = 'DEAD', dead_lettered_at = CURRENT_TIMESTAMP,
                       last_error = EXCLUDED.last_error, updated_at = CURRENT_TIMESTAMP
                 WHERE sys_domain_event_inbox.payload_sha256 = EXCLUDED.payload_sha256
                """, eventParameters(consumerName, event, payload, payloadHash)
                        .addValue("reason", truncate(reason, 1000)));
    }

    public boolean replayDead(
            String consumerName,
            UUID eventId,
            String actor,
            String reason) {
        int changed = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'REPLAY_PENDING', attempt_count = 0,
                       available_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, lock_token = NULL, locked_until = NULL,
                       dead_lettered_at = NULL, last_error = NULL,
                       replay_count = replay_count + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                   AND status = 'DEAD'
                """, new MapSqlParameterSource()
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", eventId));
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO sys_domain_event_replay_audit (
                        replay_request_id, direction, target_id, consumer_name,
                        requested_by, reason, requested_at)
                    VALUES (
                        :requestId, 'INBOX', :targetId, :consumerName,
                        :actor, :reason, CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                            .addValue("requestId", UUID.randomUUID())
                            .addValue("targetId", eventId.toString())
                            .addValue("consumerName", consumerName)
                            .addValue("actor", actor)
                            .addValue("reason", reason));
        }
        return changed == 1;
    }

    private Optional<InboxRow> lockInbox(String consumerName, UUID eventId) {
        return jdbc.query("""
                SELECT status, payload_sha256, attempt_count, available_at, locked_until
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", eventId),
                (result, ignored) -> new InboxRow(
                        result.getString("status"),
                        result.getString("payload_sha256"),
                        result.getInt("attempt_count"),
                        result.getObject("available_at", Instant.class),
                        result.getObject("locked_until", Instant.class)))
                .stream().findFirst();
    }

    private void markDuplicate(String consumerName, UUID eventId, long lastSequence) {
        jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DUPLICATE',
                       last_error = :reason,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """, new MapSqlParameterSource()
                        .addValue("reason", "Aggregate sequence already applied: " + lastSequence)
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", eventId));
    }

    private void deferOutOfOrder(String consumerName, UUID eventId, long expectedSequence) {
        jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DEFERRED',
                       available_at = CURRENT_TIMESTAMP + INTERVAL '5 seconds',
                       last_error = :reason,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """, new MapSqlParameterSource()
                        .addValue("reason", "Waiting for aggregate sequence " + expectedSequence)
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", eventId));
    }

    private void markPermanentFailure(String consumerName, UUID eventId, String reason) {
        jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DEAD', dead_lettered_at = CURRENT_TIMESTAMP,
                       last_error = :reason, updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """, new MapSqlParameterSource()
                        .addValue("reason", reason)
                        .addValue("consumerName", consumerName)
                        .addValue("eventId", eventId));
    }

    private MapSqlParameterSource eventParameters(
            String consumerName,
            DomainEventEnvelope event,
            String payload,
            String payloadHash) {
        return new MapSqlParameterSource()
                .addValue("consumerName", consumerName)
                .addValue("eventId", event.id())
                .addValue("eventSource", event.source())
                .addValue("eventType", event.type())
                .addValue("schemaVersion", event.schemaVersion())
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

    private MapSqlParameterSource offsetParameters(
            String consumerName,
            DomainEventEnvelope event) {
        return new MapSqlParameterSource()
                .addValue("consumerName", consumerName)
                .addValue("tenantScope", event.tenantId() == null ? 0L : event.tenantId())
                .addValue("eventSource", event.source())
                .addValue("aggregateType", event.aggregateType())
                .addValue("aggregateId", event.aggregateId());
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isBlank()) return "Unknown domain-event consumer failure";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public enum BeginState {
        ACQUIRED,
        DUPLICATE,
        OUT_OF_ORDER,
        DEFERRED,
        BUSY,
        DEAD,
        PAYLOAD_CONFLICT
    }

    public enum FailureState {
        RETRYABLE,
        DEAD
    }

    public record BeginResult(BeginState state, String lockToken, int attempt) {
    }

    private record InboxRow(
            String status,
            String payloadHash,
            int attemptCount,
            Instant availableAt,
            Instant lockedUntil) {
    }
}
