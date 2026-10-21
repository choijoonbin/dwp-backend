package com.dwp.core.event;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact shared-inbox extensions reserved for the TIM-003 employment consumer. */
public final class DomainEventInboxStrictValidationSupport {

    public static final String TIM003_CONSUMER_NAME =
            "dwp-time-server.tim003-employment-absence.v1";
    public static final String TIM003_EVENT_SOURCE = "urn:dwp:people:hris:employment";
    public static final String TIM003_EVENT_TYPE = "dwp.hris.people.employment-changed.v1";
    public static final String TIM003_AGGREGATE_TYPE = "WORK_RELATIONSHIP";
    public static final String PAYLOAD_CONFLICT_REASON =
            "Event id was reused with a different payload hash.";
    public static final String INVALID_EVENT_REASON = "REGISTERED_EVENT_CONTRACT_VIOLATION";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    DomainEventInboxStrictValidationSupport(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Persists the pre-validation envelope and digest in the caller's transaction.
     * This deliberately accepts an otherwise unsupported transport contract.
     */
    public RegistrationResult registerForStrictValidation(
            String consumerName,
            DomainEventEnvelope event) {
        requireTim003Consumer(consumerName);
        validateBoundedEnvelope(event);
        PreparedEvent prepared = prepare(event);
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
                """, eventParameters(consumerName, event, prepared));
        if (inserted != 0 && inserted != 1) {
            throw new IllegalStateException("Strict inbox registration changed an invalid row count.");
        }

        StoredEvent stored = lockStored(consumerName, event.id());
        if (prepared.payloadSha256().equals(stored.payloadSha256())) {
            return new RegistrationResult(
                    inserted == 1 ? RegistrationState.REGISTERED : RegistrationState.EXISTING,
                    prepared, stored, ClaimedIdentity.from(event));
        }

        int changed = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DEAD',
                       dead_lettered_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, lock_token = NULL, locked_until = NULL,
                       last_error = :payloadConflictReason,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                   AND payload_sha256 = :storedPayloadHash
                """, new MapSqlParameterSource()
                .addValue("payloadConflictReason", PAYLOAD_CONFLICT_REASON)
                .addValue("consumerName", consumerName)
                .addValue("eventId", event.id())
                .addValue("storedPayloadHash", stored.payloadSha256()));
        requireOne(changed, "Strict inbox payload-conflict transition");
        appendStrictAudit(
                "DOMAIN_EVENT_PAYLOAD_CONFLICT", PAYLOAD_CONFLICT_REASON,
                stored, prepared.payloadSha256(), ClaimedIdentity.from(event));
        return new RegistrationResult(
                RegistrationState.PAYLOAD_CONFLICT,
                prepared,
                stored.withDeadConflict(),
                ClaimedIdentity.from(event));
    }

    /** Marks only the exact registered digest invalid and never overwrites conflict provenance. */
    public InvalidFinalizationResult finalizeRegisteredInvalid(
            String consumerName,
            UUID eventId,
            String expectedPayloadSha256,
            String reason) {
        requireTim003Consumer(consumerName);
        Objects.requireNonNull(eventId, "eventId");
        requireDigest(expectedPayloadSha256, "expectedPayloadSha256");
        StoredEvent stored = lockStored(consumerName, eventId);
        if (!expectedPayloadSha256.equals(stored.payloadSha256())) {
            throw new IllegalStateException("Registered inbox digest changed before invalid finalization.");
        }
        if (PAYLOAD_CONFLICT_REASON.equals(stored.lastError())) {
            return new InvalidFinalizationResult(
                    InvalidFinalizationState.PAYLOAD_CONFLICT_PRESERVED, stored);
        }

        String finalReason = INVALID_EVENT_REASON + ": " + boundedReason(reason);
        if ("DEAD".equals(stored.status()) && finalReason.equals(stored.lastError())) {
            return new InvalidFinalizationResult(
                    InvalidFinalizationState.FINALIZED_DEAD, stored);
        }
        int changed = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET status = 'DEAD',
                       dead_lettered_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, lock_token = NULL, locked_until = NULL,
                       last_error = :reason,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND event_id = :eventId
                   AND payload_sha256 = :expectedPayloadHash
                   AND COALESCE(last_error, '') <> :payloadConflictReason
                """, new MapSqlParameterSource()
                .addValue("reason", finalReason)
                .addValue("consumerName", consumerName)
                .addValue("eventId", eventId)
                .addValue("expectedPayloadHash", expectedPayloadSha256)
                .addValue("payloadConflictReason", PAYLOAD_CONFLICT_REASON));
        requireOne(changed, "Strict inbox invalid finalization");
        appendStrictAudit(
                "DOMAIN_EVENT_STRICT_VALIDATION_REJECTED", finalReason,
                stored, expectedPayloadSha256, stored.identity());
        return new InvalidFinalizationResult(
                InvalidFinalizationState.FINALIZED_DEAD,
                stored.withDeadReason(finalReason));
    }

    Optional<Boolean> replayDecision(
            String consumerName,
            UUID eventId,
            String actor,
            String reason) {
        if (!TIM003_CONSUMER_NAME.equals(consumerName)) {
            return Optional.empty();
        }
        Optional<StoredEvent> storedRow = findLockedStored(consumerName, eventId);
        if (storedRow.isEmpty()) {
            return Optional.of(false);
        }
        StoredEvent stored = storedRow.get();
        if (!"DEAD".equals(stored.status())
                || !PAYLOAD_CONFLICT_REASON.equals(stored.lastError())) {
            return Optional.empty();
        }
        int inserted = jdbc.update("""
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
                .addValue("actor", requiredText(actor, "actor", 240))
                .addValue("reason", "DENIED_PAYLOAD_CONFLICT: " + boundedReason(reason)));
        requireOne(inserted, "Payload-conflict replay denial audit");
        return Optional.of(false);
    }

    PreparedEvent prepare(DomainEventEnvelope event) {
        String payload = DomainEventJson.serialize(objectMapper, event);
        return new PreparedEvent(payload, DomainEventJson.sha256(payload));
    }

    private StoredEvent lockStored(String consumerName, UUID eventId) {
        return findLockedStored(consumerName, eventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Strict inbox registration could not lock one row."));
    }

    private Optional<StoredEvent> findLockedStored(String consumerName, UUID eventId) {
        List<StoredEvent> rows = jdbc.query("""
                SELECT event_id, event_source, event_type, schema_version, tenant_id,
                       aggregate_type, aggregate_id, aggregate_sequence, correlation_id,
                       payload::text AS payload, payload_sha256, status, attempt_count,
                       available_at, locked_until, last_error
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("consumerName", consumerName)
                .addValue("eventId", eventId),
                (result, ignored) -> storedEvent(result));
        if (rows.size() > 1) {
            throw new IllegalStateException("Strict inbox registration could not lock one row.");
        }
        return rows.stream().findFirst();
    }

    private StoredEvent storedEvent(ResultSet result) throws SQLException {
        return new StoredEvent(
                (UUID) result.getObject("event_id"),
                result.getString("event_source"),
                result.getString("event_type"),
                result.getInt("schema_version"),
                result.getObject("tenant_id", Long.class),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getLong("aggregate_sequence"),
                result.getString("correlation_id"),
                result.getString("payload"),
                result.getString("payload_sha256"),
                result.getString("status"),
                result.getInt("attempt_count"),
                instant(result, "available_at"),
                instant(result, "locked_until"),
                result.getString("last_error"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private void appendStrictAudit(
            String action,
            String reason,
            StoredEvent stored,
            String incomingDigest,
            ClaimedIdentity incomingIdentity) {
        AuditEvent event = AuditEvent.builder()
                .tenantId(stored.tenantId())
                .category("SYSTEM_EVENT")
                .action(action)
                .outcome("DENIED")
                .severity("HIGH")
                .riskScore(85)
                .actorType("SERVICE")
                .actorId(TIM003_CONSUMER_NAME)
                .sourceService("dwp-time-server")
                .sourceModule("TIM003")
                .targetType("DOMAIN_EVENT_INBOX")
                .targetId(stored.eventId().toString())
                .reason(reason)
                .correlationId(stored.correlationId())
                .metadata(Map.of(
                        "consumerName", TIM003_CONSUMER_NAME,
                        "storedPayloadSha256", stored.payloadSha256(),
                        "incomingPayloadSha256", incomingDigest,
                        "storedIdentity", identityMap(stored.identity()),
                        "incomingIdentity", identityMap(incomingIdentity)))
                .retentionClass("EXTENDED")
                .build()
                .sanitized();
        int inserted = jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    attempt_count, available_at, created_at)
                VALUES (
                    :outboxId, :eventId, :tenantId, CAST(:payload AS jsonb),
                    'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", event.eventId())
                .addValue("tenantId", event.tenantId())
                .addValue("payload", json(event)));
        requireOne(inserted, "Strict inbox audit append");
    }

    private MapSqlParameterSource eventParameters(
            String consumerName,
            DomainEventEnvelope event,
            PreparedEvent prepared) {
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
                .addValue("payload", prepared.payload())
                .addValue("payloadHash", prepared.payloadSha256());
    }

    static void requireTim003Consumer(String consumerName) {
        if (!TIM003_CONSUMER_NAME.equals(consumerName)) {
            throw new IllegalArgumentException("Strict inbox operations are reserved for TIM-003.");
        }
    }

    private static void validateBoundedEnvelope(DomainEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.id(), "event.id");
        if (event.tenantId() == null || event.tenantId() <= 0) {
            throw new IllegalArgumentException("event.tenantId must be positive");
        }
        requiredText(event.source(), "event.source", 240);
        requiredText(event.type(), "event.type", 240);
        requiredText(event.aggregateType(), "event.aggregateType", 120);
        requiredText(event.aggregateId(), "event.aggregateId", 240);
        requiredText(event.correlationId(), "event.correlationId", 160);
        optionalText(event.causationId(), "event.causationId", 160);
        optionalText(event.traceParent(), "event.traceParent", 80);
        if (event.schemaVersion() < 1 || event.aggregateSequence() < 1) {
            throw new IllegalArgumentException("event version and sequence must be positive");
        }
    }

    private static String requiredText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static void optionalText(String value, String field, int maximumLength) {
        if (value != null) requiredText(value, field, maximumLength);
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
    }

    private static String boundedReason(String value) {
        String normalized = value == null || value.isBlank() ? "Unspecified" : value.strip();
        return normalized.length() <= 900 ? normalized : normalized.substring(0, 900);
    }

    private static Map<String, Object> identityMap(ClaimedIdentity identity) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("tenantId", identity.tenantId());
        value.put("source", identity.source());
        value.put("type", identity.type());
        value.put("schemaVersion", identity.schemaVersion());
        value.put("aggregateType", identity.aggregateType());
        value.put("aggregateId", identity.aggregateId());
        value.put("aggregateSequence", identity.aggregateSequence());
        return value;
    }

    private static void requireOne(int changed, String operation) {
        if (changed != 1) {
            throw new IllegalStateException(operation + " must affect exactly one row.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Strict inbox audit serialization failed.", exception);
        }
    }

    public enum RegistrationState {
        REGISTERED,
        EXISTING,
        PAYLOAD_CONFLICT
    }

    public enum InvalidFinalizationState {
        FINALIZED_DEAD,
        PAYLOAD_CONFLICT_PRESERVED
    }

    public record PreparedEvent(String payload, String payloadSha256) {
    }

    public record ClaimedIdentity(
            Long tenantId,
            String source,
            String type,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long aggregateSequence) {

        static ClaimedIdentity from(DomainEventEnvelope event) {
            return new ClaimedIdentity(
                    event.tenantId(), event.source(), event.type(), event.schemaVersion(),
                    event.aggregateType(), event.aggregateId(), event.aggregateSequence());
        }
    }

    public record StoredEvent(
            UUID eventId,
            String source,
            String type,
            int schemaVersion,
            Long tenantId,
            String aggregateType,
            String aggregateId,
            long aggregateSequence,
            String correlationId,
            String payload,
            String payloadSha256,
            String status,
            int attemptCount,
            Instant availableAt,
            Instant lockedUntil,
            String lastError) {

        public ClaimedIdentity identity() {
            return new ClaimedIdentity(
                    tenantId, source, type, schemaVersion,
                    aggregateType, aggregateId, aggregateSequence);
        }

        StoredEvent withDeadConflict() {
            return withDeadReason(PAYLOAD_CONFLICT_REASON);
        }

        StoredEvent withDeadReason(String reason) {
            return new StoredEvent(
                    eventId, source, type, schemaVersion, tenantId, aggregateType,
                    aggregateId, aggregateSequence, correlationId, payload, payloadSha256,
                    "DEAD", attemptCount, availableAt, null, reason);
        }
    }

    public record RegistrationResult(
            RegistrationState state,
            PreparedEvent incoming,
            StoredEvent stored,
            ClaimedIdentity incomingIdentity) {
    }

    public record InvalidFinalizationResult(
            InvalidFinalizationState state,
            StoredEvent stored) {
    }
}
