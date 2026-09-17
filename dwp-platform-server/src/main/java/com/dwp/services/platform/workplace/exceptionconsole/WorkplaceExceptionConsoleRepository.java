package com.dwp.services.platform.workplace.exceptionconsole;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class WorkplaceExceptionConsoleRepository {
    private final JdbcTemplate jdbc;

    public WorkplaceExceptionConsoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SafetyRow> activeSafety(long tenantId) {
        return jdbc.query("""
                SELECT incident_id, incident_number, incident_type, severity, incident_state,
                       message, safety_action, activated_at, version
                  FROM wp_safety_incidents
                 WHERE tenant_id = ? AND incident_state IN ('ACTIVE', 'CLOSURE_PENDING')
                 ORDER BY activated_at DESC, incident_id
                 LIMIT 100
                """, (rs, row) -> new SafetyRow(
                rs.getObject("incident_id", UUID.class), rs.getString("incident_number"),
                rs.getString("incident_type"), rs.getString("severity"),
                rs.getString("incident_state"), rs.getString("message"),
                rs.getString("safety_action"),
                rs.getObject("activated_at", OffsetDateTime.class), rs.getLong("version")), tenantId);
    }

    public List<BookingRow> bookingFailures(long tenantId) {
        return jdbc.query("""
                SELECT item.batch_item_id, item.batch_id, item.item_state, item.error_code,
                       item.compensation_available, item.requery_required, item.updated_at,
                       item.version, batch.correlation_id
                  FROM wp_booking_batch_items item
                  JOIN wp_booking_batches batch
                    ON batch.tenant_id = item.tenant_id AND batch.batch_id = item.batch_id
                 WHERE item.tenant_id = ?
                   AND item.item_state IN ('FAILED', 'RESULT_UNKNOWN', 'COMPENSATION_FAILED')
                   AND item.updated_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                 ORDER BY item.updated_at DESC, item.batch_item_id
                 LIMIT 100
                """, (rs, row) -> new BookingRow(
                rs.getObject("batch_item_id", UUID.class), rs.getObject("batch_id", UUID.class),
                rs.getString("item_state"), rs.getString("error_code"),
                rs.getBoolean("compensation_available"), rs.getBoolean("requery_required"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getString("correlation_id")), tenantId);
    }

    public List<ConnectorRow> connectorFailures(long tenantId) {
        return jdbc.query("""
                SELECT config.connector_kind, config.provider, config.enabled,
                       config.version AS configuration_version,
                       truth.reported_state, truth.received_at, truth.last_success_at,
                       truth.lag_seconds, truth.retry_queue_depth, truth.dead_letter_queue_depth,
                       truth.error_code, truth.version AS runtime_version,
                       replay.replay_job_id, replay.replay_state
                  FROM wp_experience_connector_configurations config
                  LEFT JOIN wp_connector_runtime_truth truth
                    ON truth.tenant_id = config.tenant_id
                   AND truth.connector_kind = config.connector_kind
                  LEFT JOIN LATERAL (
                      SELECT replay_job_id, replay_state
                        FROM wp_connector_replay_jobs
                       WHERE tenant_id = config.tenant_id
                         AND connector_kind = config.connector_kind
                         AND replay_state IN ('QUEUED','DISPATCHING','RUNNING','RESULT_UNKNOWN')
                       ORDER BY requested_at DESC LIMIT 1
                  ) replay ON TRUE
                 WHERE config.tenant_id = ? AND config.enabled = TRUE
                   AND (truth.observation_id IS NULL
                        OR truth.reported_state <> 'HEALTHY'
                        OR truth.received_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                        OR COALESCE(truth.dead_letter_queue_depth, 0) > 0)
                 ORDER BY COALESCE(truth.received_at, config.updated_at) DESC,
                          config.connector_kind
                 LIMIT 100
                """, (rs, row) -> new ConnectorRow(
                rs.getString("connector_kind"), rs.getString("provider"), rs.getBoolean("enabled"),
                rs.getLong("configuration_version"), rs.getString("reported_state"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                nullableLong(rs, "lag_seconds"), nullableLong(rs, "retry_queue_depth"),
                nullableLong(rs, "dead_letter_queue_depth"), rs.getString("error_code"),
                nullableLong(rs, "runtime_version"),
                rs.getObject("replay_job_id", UUID.class), rs.getString("replay_state")), tenantId);
    }

    public RecoveryStats recoveryStats(long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE error_code ILIKE '%409%'
                                             OR error_code ILIKE '%CONFLICT%') AS conflicts,
                       COUNT(*) FILTER (WHERE item_state IN ('SUCCEEDED','COMPENSATED')) AS recovered,
                       COUNT(*) FILTER (WHERE item_state IN ('SUCCEEDED','COMPENSATED','FAILED',
                                                             'RESULT_UNKNOWN','COMPENSATION_FAILED')) AS terminal
                  FROM wp_booking_batch_items
                 WHERE tenant_id = ?
                   AND updated_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """, (rs, row) -> new RecoveryStats(
                rs.getLong("conflicts"), rs.getLong("recovered"), rs.getLong("terminal")), tenantId);
    }

    public void lockExportPreview(long tenantId, long actorId, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-exception-export-preview:" + tenantId + ":" + actorId + ":" + idempotencyKey),
                result -> null);
    }

    public ExportPreviewRow exportPreviewByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_exception_export_previews
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, (rs, row) -> exportPreview(rs), tenantId, actorId, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    public ExportPreviewRow exportPreview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_exception_export_previews
                 WHERE tenant_id=? AND actor_user_id=? AND preview_id=?
                """, (rs, row) -> exportPreview(rs), tenantId, actorId, previewId)
                .stream().findFirst().orElse(null);
    }

    public void saveExportPreview(ExportPreviewRow row) {
        jdbc.update("""
                INSERT INTO wp_exception_export_previews(
                    preview_id,tenant_id,actor_user_id,purpose,row_count,content_sha256,
                    idempotency_key,request_fingerprint,correlation_id,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, row.previewId(), row.tenantId(), row.actorId(), row.purpose(), row.rowCount(),
                row.contentSha256(), row.idempotencyKey(), row.requestFingerprint(),
                row.correlationId(), row.expiresAt(), row.createdAt());
    }

    public void lockExportCommand(long tenantId, long actorId, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-exception-export:" + tenantId + ":" + actorId + ":" + idempotencyKey),
                result -> null);
    }

    public ExportCommandRow exportCommandByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_exception_export_commands
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, (rs, row) -> exportCommand(rs), tenantId, actorId, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    public ExportCommandRow exportCommand(long tenantId, long actorId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_exception_export_commands
                 WHERE tenant_id=? AND actor_user_id=? AND command_id=?
                """, (rs, row) -> exportCommand(rs), tenantId, actorId, commandId)
                .stream().findFirst().orElse(null);
    }

    public void saveExportCommand(ExportCommandRow row) {
        jdbc.update("""
                INSERT INTO wp_exception_export_commands(
                    command_id,preview_id,tenant_id,actor_user_id,reason,row_count,csv_content,
                    content_sha256,idempotency_key,request_fingerprint,correlation_id,
                    accepted_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.commandId(), row.previewId(), row.tenantId(), row.actorId(), row.reason(),
                row.rowCount(), row.csvContent(), row.contentSha256(), row.idempotencyKey(),
                row.requestFingerprint(), row.correlationId(), row.acceptedAt(), row.expiresAt());
    }

    private ExportPreviewRow exportPreview(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExportPreviewRow(rs.getObject("preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getString("purpose"), rs.getInt("row_count"),
                rs.getString("content_sha256"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private ExportCommandRow exportCommand(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExportCommandRow(rs.getObject("command_id", UUID.class),
                rs.getObject("preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getString("reason"), rs.getInt("row_count"),
                rs.getString("csv_content"), rs.getString("content_sha256"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("correlation_id"), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record SafetyRow(UUID id, String number, String type, String severity, String state,
                            String message, String action, OffsetDateTime detectedAt, long version) { }
    public record BookingRow(UUID id, UUID batchId, String state, String errorCode,
                             boolean compensationAvailable, boolean requeryRequired,
                             OffsetDateTime detectedAt, long version, String correlationId) { }
    public record ConnectorRow(String kind, String provider, boolean enabled,
                               long configurationVersion, String reportedState,
                               OffsetDateTime receivedAt, OffsetDateTime lastSuccessAt,
                               Long lagSeconds, Long retryQueueDepth, Long deadLetterQueueDepth,
                               String errorCode, Long runtimeVersion, UUID replayJobId,
                               String replayState) { }
    public record RecoveryStats(long conflicts, long recovered, long terminal) { }
    public record ExportPreviewRow(UUID previewId, long tenantId, long actorId, String purpose,
                                   int rowCount, String contentSha256, String idempotencyKey,
                                   String requestFingerprint, String correlationId,
                                   OffsetDateTime createdAt, OffsetDateTime expiresAt) { }
    public record ExportCommandRow(UUID commandId, UUID previewId, long tenantId, long actorId,
                                   String reason, int rowCount, String csvContent,
                                   String contentSha256, String idempotencyKey,
                                   String requestFingerprint, String correlationId,
                                   OffsetDateTime acceptedAt, OffsetDateTime expiresAt) { }
}
