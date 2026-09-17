package com.dwp.services.platform.workplace.connectorops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;

@Repository
public class WorkplaceConnectorOpsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplaceConnectorOpsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<RuntimeRow> runtimes(long tenantId) {
        return jdbc.query("""
                SELECT c.tenant_id, c.connector_kind, c.provider configured_provider, c.enabled,
                       c.configuration_reference,
                       c.version configuration_version,
                       t.observation_id, t.provider observed_provider,
                       t.configuration_version observed_configuration_version,
                       t.reported_state, t.capabilities, t.source_observed_at,
                       t.received_at, t.last_success_at, t.lag_seconds,
                       t.checkpoint_reference, t.retry_queue_depth,
                       t.dead_letter_queue_depth, t.error_code,
                       t.observation_sequence, t.version runtime_version,
                       (SELECT j.replay_job_id
                          FROM wp_connector_replay_jobs j
                         WHERE j.tenant_id = c.tenant_id
                           AND j.connector_kind = c.connector_kind
                           AND j.replay_state IN ('QUEUED', 'DISPATCHING', 'RUNNING', 'RESULT_UNKNOWN')
                         ORDER BY j.requested_at DESC LIMIT 1) active_replay_job_id
                FROM wp_experience_connector_configurations c
                LEFT JOIN wp_connector_runtime_truth t
                  ON t.tenant_id = c.tenant_id
                 AND t.connector_kind = c.connector_kind
                WHERE c.tenant_id = ?
                ORDER BY c.connector_kind
                """, this::runtime, tenantId);
    }

    public Optional<RuntimeRow> runtime(long tenantId, ConnectorKind kind) {
        return runtimes(tenantId).stream().filter(row -> row.kind() == kind).findFirst();
    }

    public boolean appendObservation(ProviderObservation observation) {
        int inserted = jdbc.update("""
                INSERT INTO wp_connector_runtime_observations (
                    observation_id, tenant_id, connector_kind, provider,
                    configuration_version, adapter_id, adapter_version, reported_state,
                    capabilities, source_observed_at, received_at, last_success_at,
                    lag_seconds, checkpoint_reference, retry_queue_depth,
                    dead_letter_queue_depth, error_code, observation_sequence,
                    payload_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (observation_id) DO NOTHING
                """, observation.observationId(), observation.tenantId(), observation.kind().name(),
                observation.provider(), observation.configurationVersion(), observation.adapterId(),
                observation.adapterVersion(), observation.reportedState().name(), json(observation.capabilities()),
                observation.sourceObservedAt(), observation.receivedAt(), observation.lastSuccessAt(),
                observation.lagSeconds(), observation.checkpointReference(), observation.retryQueueDepth(),
                observation.deadLetterQueueDepth(), observation.errorCode(), observation.sequence(),
                observation.payloadFingerprint());
        if (inserted == 0) return false;
        return jdbc.update("""
                INSERT INTO wp_connector_runtime_truth (
                    tenant_id, connector_kind, observation_id, provider,
                    configuration_version, adapter_id, adapter_version, reported_state,
                    capabilities, source_observed_at, received_at, last_success_at,
                    lag_seconds, checkpoint_reference, retry_queue_depth,
                    dead_letter_queue_depth, error_code, observation_sequence, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (tenant_id, connector_kind) DO UPDATE SET
                    observation_id = EXCLUDED.observation_id,
                    provider = EXCLUDED.provider,
                    configuration_version = EXCLUDED.configuration_version,
                    adapter_id = EXCLUDED.adapter_id,
                    adapter_version = EXCLUDED.adapter_version,
                    reported_state = EXCLUDED.reported_state,
                    capabilities = EXCLUDED.capabilities,
                    source_observed_at = EXCLUDED.source_observed_at,
                    received_at = EXCLUDED.received_at,
                    last_success_at = EXCLUDED.last_success_at,
                    lag_seconds = EXCLUDED.lag_seconds,
                    checkpoint_reference = EXCLUDED.checkpoint_reference,
                    retry_queue_depth = EXCLUDED.retry_queue_depth,
                    dead_letter_queue_depth = EXCLUDED.dead_letter_queue_depth,
                    error_code = EXCLUDED.error_code,
                    observation_sequence = EXCLUDED.observation_sequence,
                    version = wp_connector_runtime_truth.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE EXCLUDED.observation_sequence > wp_connector_runtime_truth.observation_sequence
                """, observation.tenantId(), observation.kind().name(), observation.observationId(),
                observation.provider(), observation.configurationVersion(), observation.adapterId(),
                observation.adapterVersion(), observation.reportedState().name(), json(observation.capabilities()),
                observation.sourceObservedAt(), observation.receivedAt(), observation.lastSuccessAt(),
                observation.lagSeconds(), observation.checkpointReference(), observation.retryQueueDepth(),
                observation.deadLetterQueueDepth(), observation.errorCode(), observation.sequence()) == 1;
    }

    public void lockRuntimeObservation(long tenantId, ConnectorKind kind) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-connector-observation:" + tenantId + ":" + kind.name()),
                result -> null);
    }

    public void savePreview(ReplayPreview preview, long tenantId, long actorId) {
        jdbc.update("""
                INSERT INTO wp_connector_replay_previews (
                    preview_id, tenant_id, connector_kind, provider,
                    configuration_version, runtime_version, replay_from, replay_to,
                    failed_only, maximum_records, estimated_records, eligible,
                    limitations, expires_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, preview.previewId(), tenantId, preview.kind().name(), preview.provider(),
                preview.configurationVersion(), preview.runtimeVersion(), preview.from(), preview.to(),
                preview.failedOnly(), preview.maximumRecords(), preview.estimatedRecords(), preview.eligible(),
                json(preview.limitations()), preview.expiresAt(), actorId, preview.createdAt());
    }

    public void lockPreviewCommand(long tenantId, long actorId, String idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-connector-preview:" + tenantId + ":" + actorId + ":" + idempotencyKey),
                result -> null);
    }

    public Optional<PreviewCommandRow> previewByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT c.command_id, c.tenant_id, c.connector_kind, c.requested_by,
                       c.idempotency_key, c.request_fingerprint, c.correlation_id,
                       c.accepted_at, p.*
                  FROM wp_connector_replay_preview_commands c
                  JOIN wp_connector_replay_previews p
                    ON p.tenant_id = c.tenant_id
                   AND p.connector_kind = c.connector_kind
                   AND p.preview_id = c.preview_id
                 WHERE c.tenant_id = ? AND c.requested_by = ? AND c.idempotency_key = ?
                """, this::previewCommand, tenantId, actorId, idempotencyKey)
                .stream().findFirst();
    }

    public void savePreviewCommand(PreviewCommandRow row) {
        jdbc.update("""
                INSERT INTO wp_connector_replay_preview_commands (
                    command_id, tenant_id, connector_kind, requested_by,
                    idempotency_key, request_fingerprint, preview_id,
                    correlation_id, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.commandId(), row.tenantId(), row.kind().name(), row.requestedBy(),
                row.idempotencyKey(), row.requestFingerprint(), row.preview().previewId(),
                row.correlationId(), row.acceptedAt());
    }

    public Optional<ReplayPreview> preview(long tenantId, ConnectorKind kind, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_previews
                 WHERE tenant_id = ? AND connector_kind = ? AND preview_id = ?
                """, this::preview, tenantId, kind.name(), previewId).stream().findFirst();
    }

    public Optional<ReplayPreview> previewOwnedBy(
            long tenantId, ConnectorKind kind, UUID previewId, long actorId) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_previews
                 WHERE tenant_id = ? AND connector_kind = ? AND preview_id = ?
                   AND created_by = ?
                """, this::preview, tenantId, kind.name(), previewId, actorId)
                .stream().findFirst();
    }

    public void lockConnectorReplay(long tenantId, ConnectorKind kind) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-connector-replay:" + tenantId + ":" + kind.name()),
                result -> null);
    }

    public Optional<ReplayJobRow> activeReplay(long tenantId, ConnectorKind kind) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_jobs
                 WHERE tenant_id = ? AND connector_kind = ?
                   AND replay_state IN ('QUEUED', 'DISPATCHING', 'RUNNING', 'RESULT_UNKNOWN')
                 ORDER BY requested_at, replay_job_id
                 LIMIT 1
                """, this::replayJob, tenantId, kind.name()).stream().findFirst();
    }

    public Optional<ReplayJobRow> replayByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_jobs
                 WHERE tenant_id = ? AND requested_by = ? AND idempotency_key = ?
                """, this::replayJob, tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    public boolean createReplay(ReplayJobRow row) {
        return jdbc.update("""
                INSERT INTO wp_connector_replay_jobs (
                    replay_job_id, preview_id, tenant_id, connector_kind, provider,
                    configuration_version, runtime_version, replay_state, reason,
                    credential_reference,
                    idempotency_key, request_fingerprint, requested_by, correlation_id,
                    sensitive_payload_expires_at, version, requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, requested_by, idempotency_key) DO NOTHING
                """, row.jobId(), row.previewId(), row.tenantId(), row.kind().name(), row.provider(),
                row.configurationVersion(), row.runtimeVersion(), row.state().name(), row.reason(),
                row.credentialReference(),
                row.idempotencyKey(), row.requestFingerprint(), row.requestedBy(), row.correlationId(),
                row.sensitivePayloadExpiresAt(), row.version(), row.requestedAt(), row.updatedAt()) == 1;
    }

    public Optional<ReplayJobRow> replay(long tenantId, ConnectorKind kind, UUID jobId) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_jobs
                 WHERE tenant_id = ? AND connector_kind = ? AND replay_job_id = ?
                """, this::replayJob, tenantId, kind.name(), jobId).stream().findFirst();
    }

    public List<ReplayJobRow> queuedReplays(int limit) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_jobs
                 WHERE replay_state = 'QUEUED'
                 ORDER BY requested_at, replay_job_id
                 LIMIT ?
                """, this::replayJob, limit);
    }

    public List<ReplayJobRow> reconciliationCandidates(int limit) {
        return reconciliationCandidates(limit, OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    List<ReplayJobRow> reconciliationCandidates(int limit, OffsetDateTime now) {
        return jdbc.query("""
                SELECT * FROM wp_connector_replay_jobs
                 WHERE replay_state IN ('DISPATCHING', 'RUNNING', 'RESULT_UNKNOWN')
                   AND (next_reconcile_at IS NULL OR next_reconcile_at <= ?)
                 ORDER BY next_reconcile_at NULLS FIRST, updated_at, replay_job_id
                 LIMIT ?
                """, this::replayJob, now, limit);
    }

    public boolean scheduleReplayReconciliation(
            long tenantId,
            ConnectorKind kind,
            UUID jobId,
            long expectedVersion,
            ReplayState state,
            String providerOperationReference,
            String resultCode,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_connector_replay_jobs
                   SET replay_state = ?,
                       provider_operation_reference = COALESCE(?, provider_operation_reference),
                       result_summary = ?,
                       reconcile_attempt_count = reconcile_attempt_count + 1,
                       next_reconcile_at = ? + CASE LEAST(reconcile_attempt_count, 6)
                           WHEN 0 THEN INTERVAL '5 seconds'
                           WHEN 1 THEN INTERVAL '10 seconds'
                           WHEN 2 THEN INTERVAL '20 seconds'
                           WHEN 3 THEN INTERVAL '40 seconds'
                           WHEN 4 THEN INTERVAL '80 seconds'
                           WHEN 5 THEN INTERVAL '160 seconds'
                           ELSE INTERVAL '300 seconds' END,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND connector_kind = ? AND replay_job_id = ?
                   AND version = ?
                   AND replay_state IN ('DISPATCHING', 'RUNNING', 'RESULT_UNKNOWN')
                """, state.name(), providerOperationReference, resultCode, now, now,
                tenantId, kind.name(), jobId, expectedVersion) == 1;
    }

    public List<RuntimeRow> observableConfigurations(int limit) {
        return jdbc.query("""
                SELECT c.tenant_id, c.connector_kind, c.provider configured_provider, c.enabled,
                       c.configuration_reference, c.version configuration_version,
                       t.observation_id, t.provider observed_provider,
                       t.configuration_version observed_configuration_version,
                       t.reported_state, t.capabilities, t.source_observed_at,
                       t.received_at, t.last_success_at, t.lag_seconds,
                       t.checkpoint_reference, t.retry_queue_depth,
                       t.dead_letter_queue_depth, t.error_code,
                       t.observation_sequence, t.version runtime_version,
                       NULL::uuid active_replay_job_id
                  FROM wp_experience_connector_configurations c
                  LEFT JOIN wp_connector_runtime_truth t
                    ON t.tenant_id = c.tenant_id AND t.connector_kind = c.connector_kind
                 WHERE c.enabled = TRUE AND c.provider IS NOT NULL
                   AND c.configuration_reference IS NOT NULL
                 ORDER BY t.received_at NULLS FIRST, c.tenant_id, c.connector_kind
                 LIMIT ?
                """, this::runtime, limit);
    }

    public int purgeExpiredSensitivePayloads(int limit, OffsetDateTime now) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT replay_job_id
                      FROM wp_connector_replay_jobs
                     WHERE sensitive_payload_expires_at <= ?
                       AND sensitive_payload_purged_at IS NULL
                     ORDER BY sensitive_payload_expires_at, replay_job_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE wp_connector_replay_jobs job
                   SET reason = '[REDACTED]', result_summary = NULL,
                       sensitive_payload_purged_at = ?, updated_at = ?
                  FROM expired
                 WHERE job.replay_job_id = expired.replay_job_id
                """, now, limit, now, now);
    }

    public boolean changeReplayState(
            long tenantId,
            ConnectorKind kind,
            UUID jobId,
            long expectedVersion,
            List<ReplayState> allowedCurrentStates,
            ReplayState newState,
            String providerOperationReference,
            String resultSummary,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime now) {
        String[] allowed = allowedCurrentStates.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.update("""
                UPDATE wp_connector_replay_jobs
                   SET replay_state = ?, provider_operation_reference = COALESCE(?, provider_operation_reference),
                       result_summary = ?, started_at = COALESCE(started_at, ?),
                       finished_at = ?,
                       next_reconcile_at = CASE WHEN ? IN ('SUCCEEDED','FAILED')
                           THEN NULL ELSE next_reconcile_at END,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND connector_kind = ? AND replay_job_id = ?
                   AND version = ? AND replay_state = ANY (?::text[])
                """, newState.name(), providerOperationReference, resultSummary, startedAt, finishedAt,
                newState.name(), now,
                tenantId, kind.name(), jobId, expectedVersion, allowed) == 1;
    }

    private RuntimeRow runtime(ResultSet rs, int row) throws SQLException {
        Long observedConfigurationVersion = nullableLong(rs, "observed_configuration_version");
        Long runtimeVersion = nullableLong(rs, "runtime_version");
        return new RuntimeRow(
                rs.getLong("tenant_id"), ConnectorKind.valueOf(rs.getString("connector_kind")),
                rs.getString("configured_provider"), rs.getBoolean("enabled"),
                rs.getString("configuration_reference"),
                rs.getLong("configuration_version"), rs.getObject("observation_id", UUID.class),
                rs.getString("observed_provider"), observedConfigurationVersion,
                enumOrNull(ProviderReportedState.class, rs.getString("reported_state")),
                capabilities(rs.getString("capabilities")),
                rs.getObject("source_observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                nullableLong(rs, "lag_seconds"), rs.getString("checkpoint_reference"),
                nullableLong(rs, "retry_queue_depth"), nullableLong(rs, "dead_letter_queue_depth"),
                rs.getString("error_code"), nullableLong(rs, "observation_sequence"), runtimeVersion,
                rs.getObject("active_replay_job_id", UUID.class));
    }

    private ReplayPreview preview(ResultSet rs, int row) throws SQLException {
        return new ReplayPreview(rs.getObject("preview_id", UUID.class),
                ConnectorKind.valueOf(rs.getString("connector_kind")), rs.getString("provider"),
                rs.getObject("replay_from", OffsetDateTime.class), rs.getObject("replay_to", OffsetDateTime.class),
                rs.getBoolean("failed_only"), rs.getInt("maximum_records"), rs.getLong("estimated_records"),
                rs.getBoolean("eligible"), strings(rs.getString("limitations")),
                rs.getLong("configuration_version"), rs.getLong("runtime_version"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private PreviewCommandRow previewCommand(ResultSet rs, int row) throws SQLException {
        return new PreviewCommandRow(
                rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                ConnectorKind.valueOf(rs.getString("connector_kind")),
                rs.getLong("requested_by"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), preview(rs, row),
                rs.getString("correlation_id"),
                rs.getObject("accepted_at", OffsetDateTime.class));
    }

    private ReplayJobRow replayJob(ResultSet rs, int row) throws SQLException {
        return new ReplayJobRow(rs.getObject("replay_job_id", UUID.class),
                rs.getObject("preview_id", UUID.class), rs.getLong("tenant_id"),
                ConnectorKind.valueOf(rs.getString("connector_kind")), rs.getString("provider"),
                ReplayState.valueOf(rs.getString("replay_state")), rs.getString("reason"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getLong("requested_by"), rs.getString("correlation_id"),
                rs.getString("provider_operation_reference"), rs.getString("result_summary"),
                rs.getString("credential_reference"),
                rs.getLong("configuration_version"), rs.getLong("runtime_version"),
                rs.getLong("version"), rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("sensitive_payload_expires_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Connector runtime value could not be serialized.", exception);
        }
    }

    private List<Capability> capabilities(String value) {
        if (value == null) return List.of();
        return strings(value).stream().map(Capability::valueOf).toList();
    }

    private List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            return Arrays.asList(objectMapper.readValue(value, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted connector runtime JSON is invalid.", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    public record RuntimeRow(
            long tenantId,
            ConnectorKind kind,
            String configuredProvider,
            boolean enabled,
            String configurationReference,
            long configurationVersion,
            UUID observationId,
            String observedProvider,
            Long observedConfigurationVersion,
            ProviderReportedState reportedState,
            List<Capability> capabilities,
            OffsetDateTime sourceObservedAt,
            OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            Long lagSeconds,
            String checkpointReference,
            Long retryQueueDepth,
            Long deadLetterQueueDepth,
            String errorCode,
            Long observationSequence,
            Long runtimeVersion,
            UUID activeReplayJobId) { }

    public record ReplayJobRow(
            UUID jobId,
            UUID previewId,
            long tenantId,
            ConnectorKind kind,
            String provider,
            ReplayState state,
            String reason,
            String idempotencyKey,
            String requestFingerprint,
            long requestedBy,
            String correlationId,
            String providerOperationReference,
            String resultSummary,
            String credentialReference,
            long configurationVersion,
            long runtimeVersion,
            long version,
            OffsetDateTime requestedAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime updatedAt,
            OffsetDateTime sensitivePayloadExpiresAt) { }

    public record PreviewCommandRow(
            UUID commandId,
            long tenantId,
            ConnectorKind kind,
            long requestedBy,
            String idempotencyKey,
            String requestFingerprint,
            ReplayPreview preview,
            String correlationId,
            OffsetDateTime acceptedAt) { }
}
