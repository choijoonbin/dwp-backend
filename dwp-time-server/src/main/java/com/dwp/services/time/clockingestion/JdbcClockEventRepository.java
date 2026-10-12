package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAppend;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEvent;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter that commits receipt, immutable fact, and outbox envelope atomically. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class JdbcClockEventRepository implements ClockEventRepository {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";
    private static final String SELECT_RECEIPT_SQL = """
            SELECT public_id, tenant_id, idempotency_key, command_type, actor_id,
                   subject_principal_public_id, population_public_id, population_revision,
                   grant_revision, membership_revision, population_scope_digest,
                   field_policy_revision, purpose_code, authorization_revision,
                   request_digest, result_event_public_id, result_digest, correlation_id, created_at
              FROM tme_clock_command_receipts
             WHERE tenant_id = ?
               AND subject_principal_public_id = ?
               AND command_type = ?
               AND idempotency_key = ?
             FOR UPDATE
            """;
    private static final String EVENT_COLUMNS = """
            event_order, public_id, tenant_id, source_public_id, source_event_key,
            source_sequence, source_type, device_public_id, capture_mode,
            offline_batch_public_id, worker_public_id, people_assignment_public_id,
            people_assignment_revision, event_type, occurred_at, received_at,
            source_local_datetime, source_time_zone, source_utc_offset_minutes,
            dst_resolution, instant_authority, tzdb_version, time_zone_rule_version,
            source_provenance_digest, payload_digest, trust_level,
            voids_event_public_id, correction_reason_code, correlation_id, recorded_by
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public JdbcClockEventRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
    }

    @Override
    public ClockCommandReceipt append(ClockAppend append) {
        Objects.requireNonNull(append, "append must not be null");
        try {
            return inTenantTransaction(append.authority().tenantId(), () -> appendInTransaction(append));
        } catch (DataIntegrityViolationException conflict) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Clock event order, source identity, or corrective void conflicts.",
                    conflict);
        }
    }

    @Override
    public Optional<ClockEvent> find(long tenantId, UUID clockEventId) {
        requireTenant(tenantId);
        Objects.requireNonNull(clockEventId, "clockEventId must not be null");
        return inTenantTransaction(tenantId, () -> exact(jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM tme_clock_events "
                        + "WHERE tenant_id = ? AND public_id = ?",
                JdbcClockEventRepository::mapEvent,
                tenantId,
                clockEventId)));
    }

    @Override
    public List<ClockEvent> findRecent(long tenantId, Set<UUID> workerScope, int limit) {
        requireTenant(tenantId);
        if (workerScope == null || workerScope.isEmpty() || workerScope.size() > 1_000) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (limit < 1 || limit > 1_001) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return inTenantTransaction(tenantId, () -> namedJdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM tme_clock_events "
                        + "WHERE tenant_id = :tenantId AND worker_public_id IN (:workers) "
                        + "ORDER BY event_order DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workers", workerScope)
                        .addValue("limit", limit),
                JdbcClockEventRepository::mapEvent));
    }

    private ClockCommandReceipt appendInTransaction(ClockAppend append) {
        var authority = append.authority();
        ClockEvent event = append.event();
        UUID proposedReceiptId = UUID.randomUUID();
        Instant createdAt = event.receivedAt();
        int inserted = jdbc.update("""
                INSERT INTO tme_clock_command_receipts (
                    public_id, tenant_id, idempotency_key, command_type, actor_id,
                    subject_principal_public_id, population_public_id, population_revision,
                    grant_revision, membership_revision, population_scope_digest,
                    field_policy_revision, purpose_code, authorization_revision,
                    request_digest, result_event_public_id, result_digest,
                    status, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'SUCCEEDED', ?, ?)
                ON CONFLICT (
                    tenant_id, subject_principal_public_id, command_type, idempotency_key
                ) DO NOTHING
                """,
                proposedReceiptId,
                authority.tenantId(),
                append.idempotencyKey(),
                append.commandType(),
                authority.actorId(),
                authority.subjectPrincipalPublicId(),
                authority.populationPublicId(),
                authority.populationRevision(),
                authority.grantRevision(),
                authority.membershipRevision(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.purposeCode(),
                authority.authorizationRevision(),
                append.requestDigest(),
                event.clockEventId(),
                event.payloadDigest(),
                authority.correlationId(),
                Timestamp.from(createdAt));

        StoredReceipt stored = exactlyOne(jdbc.query(
                SELECT_RECEIPT_SQL,
                JdbcClockEventRepository::mapReceipt,
                authority.tenantId(),
                authority.subjectPrincipalPublicId(),
                append.commandType(),
                append.idempotencyKey()));
        requireExactBinding(append, stored);
        if (inserted == 0) return stored.toView(true);
        if (!stored.receiptId().equals(proposedReceiptId)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }

        Long eventOrder = jdbc.queryForObject("""
                INSERT INTO tme_clock_events (
                    public_id, tenant_id, command_receipt_public_id,
                    source_public_id, source_event_key, source_sequence, source_type,
                    device_public_id, capture_mode, offline_batch_public_id,
                    worker_public_id, people_assignment_public_id, people_assignment_revision,
                    event_type, occurred_at, received_at, source_local_datetime,
                    source_time_zone, source_utc_offset_minutes, dst_resolution,
                    instant_authority, tzdb_version, time_zone_rule_version,
                    source_provenance_digest, payload_digest, trust_level,
                    voids_event_public_id, correction_reason_code, correlation_id, recorded_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?)
                RETURNING event_order
                """,
                Long.class,
                event.clockEventId(),
                event.tenantId(),
                proposedReceiptId,
                event.sourcePublicId(),
                event.sourceEventKey(),
                event.sourceSequence(),
                event.sourceType().name(),
                event.devicePublicId(),
                event.captureMode().name(),
                event.offlineBatchPublicId(),
                event.workerPublicId(),
                event.peopleAssignmentPublicId(),
                event.peopleAssignmentRevision(),
                event.eventType().name(),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(event.receivedAt()),
                event.sourceLocalDateTime(),
                event.sourceTimeZone(),
                event.sourceUtcOffsetMinutes(),
                event.dstResolution().name(),
                event.instantAuthority().name(),
                event.tzdbVersion(),
                event.timeZoneRuleVersion(),
                event.sourceProvenanceDigest(),
                event.payloadDigest(),
                event.trustLevel().name(),
                event.voidsClockEventId(),
                event.correctionReasonCode(),
                event.correlationId(),
                event.recordedBy());
        if (eventOrder == null || eventOrder <= 0) {
            throw new IllegalStateException("Clock event order was not allocated");
        }
        insertOutbox(event, eventOrder, proposedReceiptId);
        return stored.toView(false);
    }

    private void insertOutbox(ClockEvent event, long eventOrder, UUID receiptId) {
        UUID outboxEventId = UUID.nameUUIDFromBytes(
                (event.clockEventId() + "|ClockEventRecorded.v1")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clockEventId", event.clockEventId());
        data.put("workerId", event.workerPublicId());
        data.put("eventType", event.eventType().name());
        data.put("occurredAt", event.occurredAt());
        data.put("sourceId", event.sourcePublicId());
        data.put("sourceOffsetMinutes", event.sourceUtcOffsetMinutes());
        data.put("payloadDigest", event.payloadDigest());
        data.put("sourceSequence", event.sourceSequence());
        data.put("sourceProvenanceDigest", event.sourceProvenanceDigest());
        data.put("voidsClockEventId", event.voidsClockEventId());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Clock outbox payload cannot be serialized", impossible);
        }
        jdbc.update("""
                INSERT INTO tme_clock_outbox_events (
                    tenant_id, event_id, aggregate_type, aggregate_public_id,
                    aggregate_sequence, schema_name, schema_version, payload,
                    payload_digest, correlation_id, causation_id, occurred_at,
                    publish_attempts
                ) VALUES (?, ?, 'ClockStream', ?, ?, 'ClockEventRecorded', 1,
                          CAST(? AS JSONB), ?, ?, ?, ?, 0)
                """,
                event.tenantId(),
                outboxEventId,
                event.workerPublicId(),
                eventOrder,
                payload,
                event.payloadDigest(),
                event.correlationId(),
                receiptId,
                Timestamp.from(event.receivedAt()));
    }

    private <T> T inTenantTransaction(long tenantId, Supplier<T> work) {
        requireTenant(tenantId);
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String configured = jdbc.queryForObject(SET_TENANT_SQL, String.class, expected);
            if (!expected.equals(configured)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "transaction returned null");
    }

    private static void requireExactBinding(ClockAppend append, StoredReceipt stored) {
        var authority = append.authority();
        boolean exact = stored.tenantId() == authority.tenantId()
                && stored.idempotencyKey().equals(append.idempotencyKey())
                && stored.commandType().equals(append.commandType())
                && stored.actorId() == authority.actorId()
                && stored.subjectPrincipalPublicId().equals(authority.subjectPrincipalPublicId())
                && stored.populationPublicId().equals(authority.populationPublicId())
                && stored.populationRevision() == authority.populationRevision()
                && stored.grantRevision() == authority.grantRevision()
                && stored.membershipRevision() == authority.membershipRevision()
                && stored.populationScopeDigest().equals(authority.populationScopeDigest())
                && stored.fieldPolicyRevision().equals(authority.fieldPolicyRevision())
                && stored.purposeCode().equals(authority.purposeCode())
                && stored.authorizationRevision().equals(authority.authorizationRevision())
                && stored.requestDigest().equals(append.requestDigest())
                && stored.eventId().equals(append.event().clockEventId())
                && stored.resultDigest().equals(append.event().payloadDigest());
        if (!exact) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Idempotency-Key was already used for different clock evidence.");
        }
    }

    private static StoredReceipt mapReceipt(ResultSet row, int ignored) throws SQLException {
        return new StoredReceipt(
                row.getObject("public_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("idempotency_key", UUID.class),
                row.getString("command_type"),
                row.getLong("actor_id"),
                row.getObject("subject_principal_public_id", UUID.class),
                row.getObject("population_public_id", UUID.class),
                row.getLong("population_revision"),
                row.getLong("grant_revision"),
                row.getLong("membership_revision"),
                row.getString("population_scope_digest").trim(),
                row.getString("field_policy_revision"),
                row.getString("purpose_code"),
                row.getString("authorization_revision"),
                row.getString("request_digest").trim(),
                row.getObject("result_event_public_id", UUID.class),
                row.getString("result_digest").trim(),
                row.getObject("correlation_id", UUID.class),
                row.getTimestamp("created_at").toInstant());
    }

    private static ClockEvent mapEvent(ResultSet row, int ignored) throws SQLException {
        Object sourceSequence = row.getObject("source_sequence");
        return new ClockEvent(
                row.getObject("public_id", UUID.class),
                row.getLong("event_order"),
                row.getLong("tenant_id"),
                row.getObject("source_public_id", UUID.class),
                row.getString("source_event_key"),
                sourceSequence == null ? null : ((Number) sourceSequence).longValue(),
                ClockSourceType.valueOf(row.getString("source_type")),
                row.getObject("device_public_id", UUID.class),
                CaptureMode.valueOf(row.getString("capture_mode")),
                row.getObject("offline_batch_public_id", UUID.class),
                row.getObject("worker_public_id", UUID.class),
                row.getObject("people_assignment_public_id", UUID.class),
                row.getLong("people_assignment_revision"),
                ClockEventType.valueOf(row.getString("event_type")),
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("received_at").toInstant(),
                row.getObject("source_local_datetime", java.time.LocalDateTime.class),
                row.getString("source_time_zone"),
                row.getInt("source_utc_offset_minutes"),
                DstResolution.valueOf(row.getString("dst_resolution")),
                InstantAuthority.valueOf(row.getString("instant_authority")),
                row.getString("tzdb_version"),
                row.getString("time_zone_rule_version"),
                row.getString("source_provenance_digest").trim(),
                row.getString("payload_digest").trim(),
                TrustLevel.valueOf(row.getString("trust_level")),
                row.getObject("voids_event_public_id", UUID.class),
                row.getString("correction_reason_code"),
                row.getObject("correlation_id", UUID.class),
                row.getLong("recorded_by"));
    }

    private static <T> Optional<T> exact(List<T> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Owner query returned multiple rows");
        return rows.stream().findFirst();
    }

    private static <T> T exactlyOne(List<T> rows) {
        if (rows.size() != 1) throw new IllegalStateException("Durable receipt was not recovered");
        return rows.getFirst();
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private record StoredReceipt(
            UUID receiptId,
            long tenantId,
            UUID idempotencyKey,
            String commandType,
            long actorId,
            UUID subjectPrincipalPublicId,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            long membershipRevision,
            String populationScopeDigest,
            String fieldPolicyRevision,
            String purposeCode,
            String authorizationRevision,
            String requestDigest,
            UUID eventId,
            String resultDigest,
            UUID correlationId,
            Instant createdAt) {

        private ClockCommandReceipt toView(boolean replayed) {
            return new ClockCommandReceipt(
                    receiptId,
                    idempotencyKey,
                    commandType,
                    eventId,
                    "SUCCEEDED",
                    requestDigest,
                    resultDigest,
                    correlationId,
                    createdAt,
                    replayed);
        }
    }
}
