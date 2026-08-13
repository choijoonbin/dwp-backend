package com.dwp.services.people.workforce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkforceExportRepository {

    private static final String SELECT = """
            SELECT workforce_export_request_id, tenant_id, requested_by,
                   dataset_key, selection::text AS selection,
                   population_type, organization_ids, field_groups, export_format,
                   masking_profile, watermark_text, recipient_reference, purpose,
                   source_reference, lifecycle_state, execution_enabled, blockers,
                   policy_snapshot::text AS policy_snapshot, request_sha256,
                   artifact_reference, artifact_sha256, artifact_size_bytes,
                   artifact_expires_at, attempt_count, retry_cycle_attempt_count,
                   manual_retry_count, next_attempt_at,
                   cancellation_requested_at, completed_at, version,
                   created_at, updated_at
              FROM ppl_workforce_export_requests
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkforceExportRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<RequestRow> list(Long tenantId, Long requestedBy, boolean governAll) {
        String ownerClause = governAll ? "" : " AND requested_by = :requestedBy";
        return jdbc.query(
                SELECT + " WHERE tenant_id = :tenantId" + ownerClause
                        + " ORDER BY created_at DESC LIMIT 100",
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("requestedBy", requestedBy),
                this::row);
    }

    public Optional<RequestRow> find(Long tenantId, Long requestedBy, UUID requestId, boolean governAll) {
        String ownerClause = governAll ? "" : " AND requested_by = :requestedBy";
        return jdbc.query(
                SELECT + " WHERE tenant_id = :tenantId"
                        + " AND workforce_export_request_id = :requestId" + ownerClause,
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("requestedBy", requestedBy)
                        .addValue("requestId", requestId),
                this::row).stream().findFirst();
    }

    public Optional<RequestRow> findByIdempotency(
            Long tenantId,
            Long requestedBy,
            String idempotencyKey) {
        return jdbc.query(
                SELECT + " WHERE tenant_id = :tenantId AND requested_by = :requestedBy"
                        + " AND idempotency_key = :idempotencyKey",
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("requestedBy", requestedBy)
                        .addValue("idempotencyKey", idempotencyKey),
                this::row).stream().findFirst();
    }

    public RequestRow create(
            Long tenantId,
            Long requestedBy,
            UUID requestId,
            WorkforceExportDtos.CreateRequest request,
            String populationType,
            List<UUID> organizationIds,
            List<String> fieldGroups,
            String maskingProfile,
            String watermarkText,
            boolean executionEnabled,
            List<String> blockers,
            String policySnapshot,
            String requestSha256) {
        String state = executionEnabled ? "QUEUED" : "BLOCKED_PENDING_APPROVAL";
        jdbc.update("""
                INSERT INTO ppl_workforce_export_requests (
                    workforce_export_request_id, tenant_id, requested_by, idempotency_key,
                    dataset_key, selection,
                    population_type, organization_ids, field_groups, export_format,
                    masking_profile, watermark_text, recipient_reference, purpose,
                    source_reference, lifecycle_state, execution_enabled, blockers,
                    policy_snapshot, request_sha256, next_attempt_at)
                VALUES (
                    :requestId, :tenantId, :requestedBy, :idempotencyKey,
                    :datasetKey, CAST(:selection AS JSONB),
                    :populationType, CAST(:organizationIds AS UUID[]),
                    CAST(:fieldGroups AS VARCHAR[]), :exportFormat, :maskingProfile,
                    :watermarkText, :recipientReference, :purpose, :sourceReference,
                    :state, :executionEnabled, CAST(:blockers AS VARCHAR[]),
                    CAST(:policySnapshot AS JSONB), :requestSha256,
                    CASE WHEN :executionEnabled THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, new MapSqlParameterSource("requestId", requestId)
                .addValue("tenantId", tenantId)
                .addValue("requestedBy", requestedBy)
                .addValue("idempotencyKey", request.idempotencyKey().trim())
                .addValue("datasetKey", request.datasetKey())
                .addValue("selection", json(request.selection()))
                .addValue("populationType", populationType)
                .addValue("organizationIds", pgArray(organizationIds.stream().map(UUID::toString).toList()))
                .addValue("fieldGroups", pgArray(fieldGroups))
                .addValue("exportFormat", request.exportFormat())
                .addValue("maskingProfile", maskingProfile)
                .addValue("watermarkText", watermarkText)
                .addValue("recipientReference", request.recipientReference().trim())
                .addValue("purpose", request.purpose().trim())
                .addValue("sourceReference", request.sourceReference().trim())
                .addValue("state", state)
                .addValue("executionEnabled", executionEnabled)
                .addValue("blockers", pgArray(blockers))
                .addValue("policySnapshot", policySnapshot)
                .addValue("requestSha256", requestSha256));
        appendEvent(requestId, tenantId, 0, executionEnabled ? "QUEUED" : "BLOCKED",
                null, executionEnabled ? null : "RELEASE_GATE_BLOCKED",
                executionEnabled ? null : "Export execution is blocked until release decisions are approved.",
                null, null);
        return find(tenantId, requestedBy, requestId, true).orElseThrow();
    }

    public RequestRow cancel(
            Long tenantId,
            Long requestedBy,
            UUID requestId,
            long version,
            String targetState,
            boolean governAll) {
        String ownerClause = governAll ? "" : " AND requested_by = :requestedBy";
        int changed = jdbc.update("""
                UPDATE ppl_workforce_export_requests
                   SET lifecycle_state = :targetState,
                       cancellation_requested_at = CURRENT_TIMESTAMP,
                       cancellation_requested_by = :requestedBy,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND workforce_export_request_id = :requestId
                   AND version = :version
                """ + ownerClause, new MapSqlParameterSource("targetState", targetState)
                .addValue("requestedBy", requestedBy)
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId)
                .addValue("version", version));
        if (changed != 1) return null;
        RequestRow updated = find(tenantId, requestedBy, requestId, true).orElseThrow();
        if ("CANCELLED".equals(targetState)) {
            appendEvent(requestId, tenantId, Math.max(0, updated.attemptCount()), "CANCELLED",
                    null, null, null, null, null);
        }
        return updated;
    }

    public RequestRow retry(
            Long tenantId,
            Long requestedBy,
            UUID requestId,
            long version,
            boolean governAll) {
        String ownerClause = governAll ? "" : " AND requested_by = :requestedBy";
        int changed = jdbc.update("""
                UPDATE ppl_workforce_export_requests
                   SET lifecycle_state = 'RETRY_WAIT', next_attempt_at = CURRENT_TIMESTAMP,
                       retry_cycle_attempt_count = 0,
                       manual_retry_count = manual_retry_count + 1,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND workforce_export_request_id = :requestId
                   AND lifecycle_state = 'FAILED'
                   AND version = :version
                """ + ownerClause, new MapSqlParameterSource("requestedBy", requestedBy)
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId)
                .addValue("version", version));
        if (changed != 1) return null;
        RequestRow updated = find(tenantId, requestedBy, requestId, true).orElseThrow();
        appendEvent(requestId, tenantId, Math.max(1, updated.attemptCount()), "RETRY_SCHEDULED",
                null, null, null, null, null);
        return updated;
    }

    @Transactional
    public List<RequestRow> claim(int batchSize, String workerReference) {
        List<RequestRow> claimed = jdbc.query("""
                WITH candidates AS (
                    SELECT workforce_export_request_id
                      FROM ppl_workforce_export_requests
                     WHERE execution_enabled = TRUE
                       AND cardinality(blockers) = 0
                       AND lifecycle_state IN ('QUEUED', 'RETRY_WAIT')
                       AND COALESCE(next_attempt_at, CURRENT_TIMESTAMP) <= CURRENT_TIMESTAMP
                     ORDER BY created_at, workforce_export_request_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT :batchSize
                ), claimed AS (
                    UPDATE ppl_workforce_export_requests request
                       SET lifecycle_state = 'RUNNING', attempt_count = attempt_count + 1,
                           retry_cycle_attempt_count = retry_cycle_attempt_count + 1,
                           next_attempt_at = NULL, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE request.workforce_export_request_id = candidates.workforce_export_request_id
                    RETURNING request.*
                )
                SELECT workforce_export_request_id, tenant_id, requested_by,
                       dataset_key, selection::text AS selection,
                       population_type, organization_ids, field_groups, export_format,
                       masking_profile, watermark_text, recipient_reference, purpose,
                       source_reference, lifecycle_state, execution_enabled, blockers,
                       policy_snapshot::text AS policy_snapshot, request_sha256,
                       artifact_reference, artifact_sha256, artifact_size_bytes,
                       artifact_expires_at, attempt_count, retry_cycle_attempt_count,
                       manual_retry_count, next_attempt_at,
                       cancellation_requested_at, completed_at, version,
                       created_at, updated_at
                  FROM claimed
                 ORDER BY created_at, workforce_export_request_id
                """, new MapSqlParameterSource("batchSize", batchSize), this::row);
        claimed.forEach(row -> appendEvent(
                row.requestId(), row.tenantId(), row.attemptCount(), "CLAIMED",
                workerReference, null, null, null, null));
        return claimed;
    }

    public void fail(
            RequestRow row,
            String targetState,
            Instant nextAttemptAt,
            String failureCode,
            String redactedMessage,
            String workerReference) {
        int changed = jdbc.update("""
                UPDATE ppl_workforce_export_requests
                   SET lifecycle_state = :targetState, next_attempt_at = :nextAttemptAt,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND workforce_export_request_id = :requestId
                   AND lifecycle_state IN ('RUNNING', 'CANCEL_REQUESTED')
                   AND version = :version
                """, new MapSqlParameterSource("targetState", targetState)
                .addValue("nextAttemptAt", nextAttemptAt)
                .addValue("tenantId", row.tenantId())
                .addValue("requestId", row.requestId())
                .addValue("version", row.version()));
        if (changed != 1) throw new IllegalStateException("The export attempt state changed.");
        appendEvent(row.requestId(), row.tenantId(), row.attemptCount(),
                "CANCELLED".equals(targetState) ? "CANCELLED"
                        : "FAILED".equals(targetState) ? "FAILED" : "RETRY_SCHEDULED",
                workerReference, failureCode, truncate(redactedMessage), null, null);
    }

    public void complete(
            RequestRow row,
            WorkforceExportDtos.ArtifactEvidence artifact,
            String workerReference) {
        int changed = jdbc.update("""
                UPDATE ppl_workforce_export_requests
                   SET lifecycle_state = 'COMPLETED', artifact_reference = :artifactReference,
                       artifact_sha256 = :artifactSha256, artifact_size_bytes = :artifactSize,
                       artifact_expires_at = :artifactExpiresAt, completed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND workforce_export_request_id = :requestId
                   AND lifecycle_state = 'RUNNING'
                   AND version = :version
                """, new MapSqlParameterSource("artifactReference", artifact.artifactReference())
                .addValue("artifactSha256", artifact.artifactSha256())
                .addValue("artifactSize", artifact.artifactSizeBytes())
                .addValue("artifactExpiresAt", artifact.artifactExpiresAt())
                .addValue("tenantId", row.tenantId())
                .addValue("requestId", row.requestId())
                .addValue("version", row.version()));
        if (changed != 1) throw new IllegalStateException("The export attempt state changed.");
        appendEvent(row.requestId(), row.tenantId(), row.attemptCount(), "COMPLETED",
                workerReference, null, null, artifact.artifactSha256(), artifact.artifactSizeBytes());
    }

    public List<WorkforceExportDtos.AttemptEvent> attempts(Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT workforce_export_attempt_event_id, attempt_number, event_type,
                       worker_reference, failure_code, redacted_failure_message,
                       artifact_sha256, artifact_size_bytes, occurred_at
                  FROM ppl_workforce_export_attempt_events
                 WHERE tenant_id = :tenantId
                   AND workforce_export_request_id = :requestId
                 ORDER BY occurred_at, workforce_export_attempt_event_id
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("requestId", requestId),
                (result, ignored) -> new WorkforceExportDtos.AttemptEvent(
                        result.getObject("workforce_export_attempt_event_id", UUID.class),
                        result.getInt("attempt_number"), result.getString("event_type"),
                        result.getString("worker_reference"), result.getString("failure_code"),
                        result.getString("redacted_failure_message"),
                        result.getString("artifact_sha256"),
                        result.getObject("artifact_size_bytes", Long.class),
                        result.getObject("occurred_at", Instant.class)));
    }

    public Optional<RequestRow> findForWorker(Long tenantId, UUID requestId) {
        return jdbc.query(
                SELECT + " WHERE tenant_id = :tenantId"
                        + " AND workforce_export_request_id = :requestId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("requestId", requestId),
                this::row).stream().findFirst();
    }

    public List<RequestRow> dueArtifacts(int batchSize) {
        return jdbc.query(
                SELECT + " WHERE lifecycle_state = 'COMPLETED'"
                        + " AND artifact_expires_at <= CURRENT_TIMESTAMP"
                        + " ORDER BY artifact_expires_at, workforce_export_request_id"
                        + " LIMIT :batchSize",
                new MapSqlParameterSource("batchSize", batchSize), this::row);
    }

    public boolean expireArtifact(RequestRow row) {
        int changed = jdbc.update("""
                UPDATE ppl_workforce_export_requests
                   SET lifecycle_state = 'EXPIRED', artifact_reference = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND workforce_export_request_id = :requestId
                   AND lifecycle_state = 'COMPLETED'
                   AND artifact_expires_at <= CURRENT_TIMESTAMP
                   AND version = :version
                """, new MapSqlParameterSource("tenantId", row.tenantId())
                .addValue("requestId", row.requestId())
                .addValue("version", row.version()));
        if (changed == 0) return false;
        appendEvent(
                row.requestId(), row.tenantId(), row.attemptCount(), "EXPIRED",
                "workforce-export-expiry", null,
                "The governed artifact retention window elapsed.",
                row.artifactSha256(), row.artifactSizeBytes());
        return true;
    }

    private void appendEvent(
            UUID requestId,
            Long tenantId,
            int attemptNumber,
            String eventType,
            String workerReference,
            String failureCode,
            String redactedMessage,
            String artifactSha256,
            Long artifactSize) {
        jdbc.update("""
                INSERT INTO ppl_workforce_export_attempt_events (
                    workforce_export_attempt_event_id, workforce_export_request_id,
                    tenant_id, attempt_number, event_type, worker_reference,
                    failure_code, redacted_failure_message, artifact_sha256,
                    artifact_size_bytes)
                VALUES (
                    :eventId, :requestId, :tenantId, :attemptNumber, :eventType,
                    :workerReference, :failureCode, :redactedMessage, :artifactSha256,
                    :artifactSize)
                """, new MapSqlParameterSource("eventId", UUID.randomUUID())
                .addValue("requestId", requestId)
                .addValue("tenantId", tenantId)
                .addValue("attemptNumber", attemptNumber)
                .addValue("eventType", eventType)
                .addValue("workerReference", workerReference)
                .addValue("failureCode", failureCode)
                .addValue("redactedMessage", redactedMessage)
                .addValue("artifactSha256", artifactSha256)
                .addValue("artifactSize", artifactSize));
    }

    private RequestRow row(ResultSet result, int ignored) throws SQLException {
        return new RequestRow(
                result.getObject("workforce_export_request_id", UUID.class),
                result.getObject("tenant_id", Long.class),
                result.getObject("requested_by", Long.class),
                result.getString("dataset_key"), result.getString("selection"),
                result.getString("population_type"), uuidArray(result.getArray("organization_ids")),
                stringArray(result.getArray("field_groups")), result.getString("export_format"),
                result.getString("masking_profile"), result.getString("watermark_text"),
                result.getString("recipient_reference"), result.getString("purpose"),
                result.getString("source_reference"), result.getString("lifecycle_state"),
                result.getBoolean("execution_enabled"), stringArray(result.getArray("blockers")),
                result.getString("policy_snapshot"), result.getString("request_sha256"),
                result.getString("artifact_reference"), result.getString("artifact_sha256"),
                result.getObject("artifact_size_bytes", Long.class),
                result.getObject("artifact_expires_at", Instant.class),
                result.getInt("attempt_count"), result.getInt("retry_cycle_attempt_count"),
                result.getInt("manual_retry_count"),
                result.getObject("next_attempt_at", Instant.class),
                result.getObject("cancellation_requested_at", Instant.class),
                result.getObject("completed_at", Instant.class), result.getLong("version"),
                result.getObject("created_at", Instant.class),
                result.getObject("updated_at", Instant.class));
    }

    private List<String> stringArray(Array value) throws SQLException {
        if (value == null) return List.of();
        return Arrays.asList((String[]) value.getArray());
    }

    private List<UUID> uuidArray(Array value) throws SQLException {
        if (value == null) return List.of();
        Object[] values = (Object[]) value.getArray();
        return Arrays.stream(values).map(item -> item instanceof UUID uuid
                ? uuid : UUID.fromString(item.toString())).toList();
    }

    private String pgArray(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "Export attempt failed.";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record RequestRow(
            UUID requestId,
            Long tenantId,
            Long requestedBy,
            String datasetKey,
            String selection,
            String populationType,
            List<UUID> organizationIds,
            List<String> fieldGroups,
            String exportFormat,
            String maskingProfile,
            String watermarkText,
            String recipientReference,
            String purpose,
            String sourceReference,
            String lifecycleState,
            boolean executionEnabled,
            List<String> blockers,
            String policySnapshot,
            String requestSha256,
            String artifactReference,
            String artifactSha256,
            Long artifactSizeBytes,
            Instant artifactExpiresAt,
            int attemptCount,
            int retryCycleAttemptCount,
            int manualRetryCount,
            Instant nextAttemptAt,
            Instant cancellationRequestedAt,
            Instant completedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public Optional<DatasetRow> dataset(String datasetKey) {
        return jdbc.query("""
                SELECT dataset_key, name, description, required_field_groups,
                       allowed_selection_keys, lifecycle_state, version
                  FROM ppl_workforce_export_datasets
                 WHERE dataset_key = :datasetKey
                """, new MapSqlParameterSource("datasetKey", datasetKey),
                (result, ignored) -> new DatasetRow(
                        result.getString("dataset_key"), result.getString("name"),
                        result.getString("description"),
                        stringArray(result.getArray("required_field_groups")),
                        stringArray(result.getArray("allowed_selection_keys")),
                        result.getString("lifecycle_state"), result.getLong("version")))
                .stream().findFirst();
    }

    public List<DatasetRow> activeDatasets() {
        return jdbc.query("""
                SELECT dataset_key, name, description, required_field_groups,
                       allowed_selection_keys, lifecycle_state, version
                  FROM ppl_workforce_export_datasets
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY name, dataset_key
                """, new MapSqlParameterSource(),
                (result, ignored) -> new DatasetRow(
                        result.getString("dataset_key"), result.getString("name"),
                        result.getString("description"),
                        stringArray(result.getArray("required_field_groups")),
                        stringArray(result.getArray("allowed_selection_keys")),
                        result.getString("lifecycle_state"), result.getLong("version")));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("The workforce export selection is invalid.", exception);
        }
    }

    public record DatasetRow(
            String datasetKey,
            String name,
            String description,
            List<String> requiredFieldGroups,
            List<String> allowedSelectionKeys,
            String lifecycleState,
            long version) {
    }
}
