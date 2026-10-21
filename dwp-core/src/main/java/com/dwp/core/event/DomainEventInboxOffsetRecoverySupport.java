package com.dwp.core.event;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact snapshot checkpoint primitive reserved for TIM-003 owner recovery. */
public final class DomainEventInboxOffsetRecoverySupport {

    public static final String RECOVERY_ACTOR = "service:dwp-time-server:tim003-owner-recovery";
    public static final String GAP_REASON_CODE = "TIM003_OWNER_SNAPSHOT_RECOVERY";
    public static final String BASELINE_REASON_CODE = "TIM003_OWNER_BASELINE";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern LOWERCASE_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    DomainEventInboxOffsetRecoverySupport(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Joins the caller transaction that also owns projection CAS. Any thrown exception must
     * therefore roll back projection, offset, settlement and both audit writes together.
     */
    public OffsetRecoveryResult recover(OffsetRecoveryRequest request) {
        validate(request);
        MapSqlParameterSource tuple = tupleParameters(request);
        int created = jdbc.update("""
                INSERT INTO sys_domain_event_offsets (
                    consumer_name, tenant_id, event_source, aggregate_type, aggregate_id,
                    last_sequence, last_event_id, updated_at)
                VALUES (
                    :consumerName, :tenantId, :eventSource, :aggregateType, :aggregateId,
                    0, NULL, CURRENT_TIMESTAMP)
                ON CONFLICT (
                    consumer_name, tenant_id, event_source, aggregate_type, aggregate_id)
                DO NOTHING
                """, tuple);
        if (created != 0 && created != 1) {
            throw new IllegalStateException("Snapshot offset creation changed an invalid row count.");
        }

        List<Long> offsets = jdbc.query("""
                SELECT last_sequence
                  FROM sys_domain_event_offsets
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                 FOR UPDATE
                """, tuple, (result, ignored) -> result.getLong("last_sequence"));
        if (offsets.size() != 1) {
            throw new IllegalStateException("Snapshot recovery could not lock one shared offset.");
        }
        long currentOffset = offsets.get(0);
        if (currentOffset != request.expectedPriorOffset()) {
            throw new OffsetRecoveryConflictException(
                    "Shared offset no longer equals the expected prior offset.");
        }

        if (request.mode() == RecoveryMode.OWNER_BASELINE) {
            requireNoInboxRows(tuple);
        } else {
            requireNoForbiddenRecoveryRows(tuple);
        }

        int offsetChanged = jdbc.update("""
                UPDATE sys_domain_event_offsets
                   SET last_sequence = :recoveredOffset,
                       last_event_id = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND last_sequence = :expectedPriorOffset
                """, tuple
                .addValue("recoveredOffset", request.recoveredOffset())
                .addValue("expectedPriorOffset", request.expectedPriorOffset()));
        requireOne(offsetChanged, "Snapshot offset compare-and-set");

        int settled = 0;
        if (request.mode() == RecoveryMode.GAP) {
            settled = jdbc.update("""
                    UPDATE sys_domain_event_inbox
                       SET status = 'DUPLICATE',
                           available_at = CURRENT_TIMESTAMP,
                           locked_by = NULL, lock_token = NULL, locked_until = NULL,
                           last_error = :settlementReason,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE consumer_name = :consumerName
                       AND tenant_id = :tenantId
                       AND event_source = :eventSource
                       AND event_type = :eventType
                       AND aggregate_type = :aggregateType
                       AND aggregate_id = :aggregateId
                       AND aggregate_sequence <= :recoveredOffset
                       AND status = 'DEFERRED'
                    """, tuple.addValue(
                    "settlementReason", "Settled by verified TIM-003 owner snapshot checkpoint."));
            if (settled < 0) {
                throw new IllegalStateException("Snapshot recovery returned an invalid settlement count.");
            }
        }

        UUID replayRequestId = UUID.randomUUID();
        Instant requestedAt = databaseTransactionInstant();
        String reasonCode = request.mode() == RecoveryMode.GAP
                ? GAP_REASON_CODE : BASELINE_REASON_CODE;
        String replayReason = replayReason(request, reasonCode);
        int replayInserted = jdbc.update("""
                INSERT INTO sys_domain_event_replay_audit (
                    replay_request_id, direction, target_id, consumer_name,
                    requested_by, reason, requested_at)
                VALUES (
                    :requestId, 'INBOX', :targetId, :consumerName,
                    :requestedBy, :reason, :requestedAt)
                """, new MapSqlParameterSource()
                .addValue("requestId", replayRequestId)
                .addValue("targetId", targetId(request))
                .addValue("consumerName", request.consumerName())
                .addValue("requestedBy", request.actor())
                .addValue("reason", replayReason)
                .addValue("requestedAt", requestedAt));
        requireOne(replayInserted, "Snapshot replay audit append");
        appendPairedAudit(request, replayRequestId, requestedAt, reasonCode, settled);
        return new OffsetRecoveryResult(
                currentOffset, request.recoveredOffset(), settled,
                replayRequestId, requestedAt, reasonCode);
    }

    private void requireNoInboxRows(MapSqlParameterSource tuple) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                """, tuple, Long.class);
        if (count == null || count != 0) {
            throw new OffsetRecoveryConflictException(
                    "Owner baseline requires zero matching inbox rows.");
        }
    }

    private void requireNoForbiddenRecoveryRows(MapSqlParameterSource tuple) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND (
                       status IN ('REPLAY_PENDING', 'DEAD')
                       OR last_error = :payloadConflictReason)
                """, tuple.addValue(
                        "payloadConflictReason",
                        DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON),
                Long.class);
        if (count == null || count != 0) {
            throw new OffsetRecoveryConflictException(
                    "Snapshot recovery cannot settle replay, dead, or payload-conflict evidence.");
        }
    }

    private Instant databaseTransactionInstant() {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                new MapSqlParameterSource(),
                OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException("Database transaction instant is unavailable.");
        }
        return value.toInstant();
    }

    private void appendPairedAudit(
            OffsetRecoveryRequest request,
            UUID replayRequestId,
            Instant requestedAt,
            String reasonCode,
            int settledRows) {
        Map<String, Object> metadata = new LinkedHashMap<>(request.auditEvidence());
        metadata.put("actor", request.actor());
        metadata.put("expectedPriorOffset", request.expectedPriorOffset());
        metadata.put("ownerContentSha256", request.ownerContentSha256());
        metadata.put("ownerScopeDigest", request.ownerScopeDigest());
        metadata.put("reasonCode", reasonCode);
        metadata.put("recoveredOffset", request.recoveredOffset());
        metadata.put("replayRequestId", replayRequestId.toString());
        metadata.put("requestedAt", requestedAt.toString());
        metadata.put("settledInboxRows", settledRows);
        metadata.put("source", request.eventSource());

        AuditEvent event = AuditEvent.builder()
                .eventId(replayRequestId)
                .occurredAt(requestedAt)
                .tenantId(request.tenantId())
                .category("SYSTEM_EVENT")
                .action("DOMAIN_EVENT_SNAPSHOT_OFFSET_RECOVERY")
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(70)
                .actorType("SERVICE")
                .actorId(request.actor())
                .sourceService("dwp-time-server")
                .sourceModule("TIM003")
                .targetType("WORK_RELATIONSHIP")
                .targetId(request.aggregateId())
                .reason(reasonCode)
                .correlationId(request.correlationId())
                .metadata(metadata)
                .retentionClass("EXTENDED")
                .build();
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
        requireOne(inserted, "Snapshot paired audit append");
    }

    private String replayReason(OffsetRecoveryRequest request, String reasonCode) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("actor", request.actor());
        reason.put("expectedPriorOffset", request.expectedPriorOffset());
        reason.put("ownerContentSha256", request.ownerContentSha256());
        reason.put("ownerScopeDigest", request.ownerScopeDigest());
        reason.put("reasonCode", reasonCode);
        reason.put("recoveredOffset", request.recoveredOffset());
        reason.put("source", request.eventSource());
        return json(reason);
    }

    private MapSqlParameterSource tupleParameters(OffsetRecoveryRequest request) {
        return new MapSqlParameterSource()
                .addValue("consumerName", request.consumerName())
                .addValue("tenantId", request.tenantId())
                .addValue("eventSource", request.eventSource())
                .addValue("eventType", request.eventType())
                .addValue("aggregateType", request.aggregateType())
                .addValue("aggregateId", request.aggregateId());
    }

    private static void validate(OffsetRecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        DomainEventInboxStrictValidationSupport.requireTim003Consumer(request.consumerName());
        if (request.tenantId() <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (!DomainEventInboxStrictValidationSupport.TIM003_EVENT_SOURCE.equals(
                request.eventSource())
                || !DomainEventInboxStrictValidationSupport.TIM003_EVENT_TYPE.equals(
                request.eventType())
                || !DomainEventInboxStrictValidationSupport.TIM003_AGGREGATE_TYPE.equals(
                request.aggregateType())) {
            throw new IllegalArgumentException("Snapshot recovery tuple is not TIM-003.");
        }
        if (request.aggregateId() == null
                || !LOWERCASE_UUID.matcher(request.aggregateId()).matches()) {
            throw new IllegalArgumentException("aggregateId must be a lowercase canonical UUID");
        }
        if (request.expectedPriorOffset() < 0
                || request.recoveredOffset() < request.expectedPriorOffset()) {
            throw new IllegalArgumentException("Snapshot recovery offsets are invalid");
        }
        if (request.mode() == RecoveryMode.OWNER_BASELINE
                && request.expectedPriorOffset() != 0) {
            throw new IllegalArgumentException("Owner baseline must start at offset zero");
        }
        if (request.mode() == RecoveryMode.GAP
                && request.recoveredOffset() <= request.expectedPriorOffset()) {
            throw new IllegalArgumentException("Gap recovery must advance the shared offset");
        }
        if (!RECOVERY_ACTOR.equals(request.actor())) {
            throw new IllegalArgumentException("Snapshot recovery actor is not authorized");
        }
        requireDigest(request.ownerContentSha256(), "ownerContentSha256");
        requireDigest(request.ownerScopeDigest(), "ownerScopeDigest");
        if (request.correlationId() == null
                || request.correlationId().isBlank()
                || request.correlationId().length() > 160) {
            throw new IllegalArgumentException("correlationId is invalid");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
    }

    private static String targetId(OffsetRecoveryRequest request) {
        return "TIM003|" + request.tenantId() + "|WORK_RELATIONSHIP|" + request.aggregateId();
    }

    private static Map<String, Object> safeAuditEvidence(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        if (source.size() > 32) {
            throw new IllegalArgumentException("auditEvidence has too many entries");
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 80) {
                throw new IllegalArgumentException("auditEvidence key is invalid");
            }
            String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (normalized.contains("password")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("cookie")
                    || normalized.contains("credential")
                    || normalized.contains("privatekey")
                    || normalized.contains("apikey")) {
                throw new IllegalArgumentException("auditEvidence contains a sensitive key");
            }
            if (!(value instanceof Number)
                    && !(value instanceof Boolean)
                    && !(value instanceof UUID)
                    && !(value instanceof String)) {
                throw new IllegalArgumentException("auditEvidence value is not scalar");
            }
            if (value instanceof String text && text.length() > 500) {
                throw new IllegalArgumentException("auditEvidence string is too long");
            }
            safe.put(key, value);
        });
        return Collections.unmodifiableMap(safe);
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
            throw new IllegalStateException("Snapshot recovery audit serialization failed.", exception);
        }
    }

    public enum RecoveryMode {
        GAP,
        OWNER_BASELINE
    }

    public record OffsetRecoveryRequest(
            String consumerName,
            long tenantId,
            String eventSource,
            String eventType,
            String aggregateType,
            String aggregateId,
            long expectedPriorOffset,
            long recoveredOffset,
            RecoveryMode mode,
            String actor,
            String ownerContentSha256,
            String ownerScopeDigest,
            String correlationId,
            Map<String, Object> auditEvidence) {

        public OffsetRecoveryRequest {
            Objects.requireNonNull(mode, "mode");
            auditEvidence = safeAuditEvidence(auditEvidence);
        }
    }

    public record OffsetRecoveryResult(
            long priorOffset,
            long recoveredOffset,
            int settledInboxRows,
            UUID replayRequestId,
            Instant requestedAt,
            String reasonCode) {
    }

    public static final class OffsetRecoveryConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public OffsetRecoveryConflictException(String message) {
            super(message);
        }
    }
}
