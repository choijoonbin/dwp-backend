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
        return activeSafety(tenantId, null, 100);
    }

    public List<SafetyRow> activeSafety(long tenantId, int limit) {
        return activeSafety(tenantId, null, limit);
    }

    public java.util.Optional<SafetyRow> activeSafety(long tenantId, UUID incidentId) {
        return activeSafety(tenantId, incidentId, 1).stream().findFirst();
    }

    private List<SafetyRow> activeSafety(long tenantId, UUID incidentId, int limit) {
        return jdbc.query("""
                SELECT incident_id, incident_number, incident_type, severity, incident_state,
                       message, safety_action, activated_at, version
                  FROM wp_safety_incidents
                 WHERE tenant_id = ? AND incident_state IN ('ACTIVE', 'CLOSURE_PENDING')
                   AND (CAST(? AS uuid) IS NULL OR incident_id=?)
                 ORDER BY activated_at DESC, incident_id
                 LIMIT ?
                """, (rs, row) -> new SafetyRow(
                rs.getObject("incident_id", UUID.class), rs.getString("incident_number"),
                rs.getString("incident_type"), rs.getString("severity"),
                rs.getString("incident_state"), rs.getString("message"),
                rs.getString("safety_action"),
                rs.getObject("activated_at", OffsetDateTime.class), rs.getLong("version")),
                tenantId, incidentId, incidentId, bounded(limit));
    }

    public List<BookingRow> bookingFailures(long tenantId) {
        return bookingFailures(tenantId, null, 250);
    }

    public List<BookingRow> bookingFailures(long tenantId, int limit) {
        return bookingFailures(tenantId, null, limit);
    }

    public java.util.Optional<BookingRow> bookingFailure(long tenantId, UUID itemId) {
        return bookingFailures(tenantId, itemId, 1).stream().findFirst();
    }

    private List<BookingRow> bookingFailures(long tenantId, UUID itemId, int limit) {
        return jdbc.query("""
                SELECT item.batch_item_id, item.batch_id, item.item_state, item.error_code,
                       item.compensation_available, item.requery_required, item.updated_at,
                       item.version, batch.correlation_id
                  FROM wp_booking_batch_items item
                  JOIN wp_booking_batches batch
                    ON batch.tenant_id = item.tenant_id AND batch.batch_id = item.batch_id
                 WHERE item.tenant_id = ?
                   AND item.item_state IN ('FAILED', 'RESULT_UNKNOWN', 'COMPENSATION_FAILED')
                   AND (CAST(? AS uuid) IS NULL OR item.batch_item_id=?)
                 ORDER BY item.updated_at DESC, item.batch_item_id
                 LIMIT ?
                """, (rs, row) -> new BookingRow(
                rs.getObject("batch_item_id", UUID.class), rs.getObject("batch_id", UUID.class),
                rs.getString("item_state"), rs.getString("error_code"),
                rs.getBoolean("compensation_available"), rs.getBoolean("requery_required"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getString("correlation_id")), tenantId, itemId, itemId, bounded(limit));
    }

    public List<OperationalRow> deviceCommandFailures(long tenantId) {
        return deviceCommandFailures(tenantId, null, 100);
    }

    public List<OperationalRow> deviceCommandFailures(long tenantId, int limit) {
        return deviceCommandFailures(tenantId, null, limit);
    }

    public java.util.Optional<OperationalRow> deviceCommandFailure(long tenantId, UUID commandId) {
        return deviceCommandFailures(tenantId, commandId, 1).stream().findFirst();
    }

    private List<OperationalRow> deviceCommandFailures(
            long tenantId, UUID commandId, int limit) {
        return jdbc.query("""
                SELECT command.command_id AS exception_id, command.device_id AS parent_id,
                       command.command_type AS operation, command.command_state AS state,
                       COALESCE(command.result_code, outbox.delivery_state) AS error_code,
                       command.updated_at AS detected_at, command.version,
                       COALESCE(outbox.attempt_count, 0) AS attempt_count,
                       outbox.delivery_state AS detail,
                       NULL::uuid AS site_id,NULL::uuid AS resource_id
                  FROM wp_navigation_device_commands command
                  LEFT JOIN LATERAL (
                      SELECT delivery_state, attempt_count
                        FROM wp_navigation_device_outbox
                       WHERE tenant_id=command.tenant_id AND command_id=command.command_id
                       ORDER BY updated_at DESC,outbox_id DESC LIMIT 1
                  ) outbox ON TRUE
                 WHERE command.tenant_id=?
                   AND (CAST(? AS uuid) IS NULL OR command.command_id=?)
                   AND (command.command_state='RESULT_UNKNOWN'
                        OR outbox.delivery_state='DEAD_LETTER'
                        OR (outbox.delivery_state='RESULT_UNKNOWN'
                            AND outbox.attempt_count>=10))
                 ORDER BY command.updated_at DESC,command.command_id
                 LIMIT ?
                """, this::operationalRow, tenantId, commandId, commandId, bounded(limit));
    }

    public List<OperationalRow> serviceOrderFailures(long tenantId) {
        return serviceOrderFailures(tenantId, null, 100);
    }

    public List<OperationalRow> serviceOrderFailures(long tenantId, int limit) {
        return serviceOrderFailures(tenantId, null, limit);
    }

    public java.util.Optional<OperationalRow> serviceOrderFailure(long tenantId, UUID orderId) {
        return serviceOrderFailures(tenantId, orderId, 1).stream().findFirst();
    }

    private List<OperationalRow> serviceOrderFailures(long tenantId, UUID orderId, int limit) {
        return jdbc.query("""
                SELECT orders.service_order_id AS exception_id,
                       orders.reservation_id AS parent_id,
                       orders.reservation_authority AS operation,
                       orders.order_state AS state,
                       COALESCE(orders.result_detail, 'SERVICE_ORDER_RESULT_UNKNOWN') AS error_code,
                       orders.updated_at AS detected_at, orders.version,
                       0 AS attempt_count, orders.reservation_impact AS detail,
                       NULL::uuid AS site_id,NULL::uuid AS resource_id
                  FROM wp_service_orders orders
                 WHERE orders.tenant_id=?
                   AND (CAST(? AS uuid) IS NULL OR orders.service_order_id=?)
                   AND (orders.order_state='RESULT_UNKNOWN'
                        OR EXISTS (
                            SELECT 1 FROM wp_service_order_lines line
                             WHERE line.tenant_id=orders.tenant_id
                               AND line.service_order_id=orders.service_order_id
                               AND line.line_state='RESULT_UNKNOWN')
                        OR EXISTS (
                            SELECT 1 FROM wp_service_fulfillment_tasks task
                             WHERE task.tenant_id=orders.tenant_id
                               AND task.service_order_id=orders.service_order_id
                               AND task.task_state='RESULT_UNKNOWN'))
                 ORDER BY orders.updated_at DESC,orders.service_order_id
                 LIMIT ?
                """, this::operationalRow, tenantId, orderId, orderId, bounded(limit));
    }

    public List<ServiceProviderRow> serviceProviderFailures(long tenantId) {
        return serviceProviderFailures(tenantId, null, 100);
    }

    public List<ServiceProviderRow> serviceProviderFailures(long tenantId, int limit) {
        return serviceProviderFailures(tenantId, null, limit);
    }

    public java.util.Optional<ServiceProviderRow> serviceProviderFailure(
            long tenantId, String providerCode) {
        return serviceProviderFailures(tenantId, providerCode, 1).stream().findFirst();
    }

    private List<ServiceProviderRow> serviceProviderFailures(
            long tenantId, String providerCode, int limit) {
        return jdbc.query("""
                SELECT provider_code, reported_state, error_code, received_at,
                       updated_at, configuration_version, observed_configuration_version,
                       version
                  FROM wp_service_provider_truth
                 WHERE tenant_id=? AND configured=TRUE
                   AND (CAST(? AS varchar) IS NULL OR provider_code=?)
                   AND (reported_state IS NULL OR reported_state<>'HEALTHY'
                        OR received_at IS NULL
                        OR received_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes'
                        OR observed_configuration_version IS DISTINCT FROM configuration_version)
                 ORDER BY COALESCE(received_at,updated_at) DESC,provider_code
                 LIMIT ?
                """, (rs, row) -> new ServiceProviderRow(
                rs.getString("provider_code"), rs.getString("reported_state"),
                rs.getString("error_code"), rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("configuration_version"),
                nullableLong(rs, "observed_configuration_version"), rs.getLong("version")),
                tenantId, providerCode, providerCode, bounded(limit));
    }

    public List<OperationalRow> serviceRefundFailures(long tenantId) {
        return serviceRefundFailures(tenantId, null, 100);
    }

    public List<OperationalRow> serviceRefundFailures(long tenantId, int limit) {
        return serviceRefundFailures(tenantId, null, limit);
    }

    public java.util.Optional<OperationalRow> serviceRefundFailure(
            long tenantId, UUID adjustmentId) {
        return serviceRefundFailures(tenantId, adjustmentId, 1).stream().findFirst();
    }

    private List<OperationalRow> serviceRefundFailures(
            long tenantId, UUID adjustmentId, int limit) {
        return jdbc.query("""
                SELECT adjustment.line_adjustment_id AS exception_id,
                       adjustment.service_order_id AS parent_id,
                       adjustment.adjustment_type AS operation,
                       adjustment.adjustment_state AS state,
                       COALESCE(adjustment.result_detail,
                                'SERVICE_REFUND_RECONCILIATION_REQUIRED') AS error_code,
                       adjustment.updated_at AS detected_at, adjustment.version,
                       adjustment.provider_recovery_attempt_count AS attempt_count,
                       adjustment.refund_scope AS detail,
                       NULL::uuid AS site_id,NULL::uuid AS resource_id
                  FROM wp_service_line_adjustments adjustment
                 WHERE adjustment.tenant_id=?
                   AND (CAST(? AS uuid) IS NULL OR adjustment.line_adjustment_id=?)
                   AND (adjustment.adjustment_state IN
                          ('RECONCILIATION_PENDING','RESULT_UNKNOWN','REFUND_NOT_CONFIGURED',
                           'FAILED')
                        OR (adjustment.adjustment_state='CANCELLATION_PENDING'
                            AND adjustment.provider_recovery_attempt_count>=10))
                 ORDER BY adjustment.updated_at DESC,adjustment.line_adjustment_id
                 LIMIT ?
                """, this::operationalRow, tenantId, adjustmentId, adjustmentId,
                bounded(limit));
    }

    public List<OperationalRow> visitProviderFailures(long tenantId) {
        return visitProviderFailures(tenantId, null, 100);
    }

    public List<OperationalRow> visitProviderFailures(long tenantId, int limit) {
        return visitProviderFailures(tenantId, null, limit);
    }

    public java.util.Optional<OperationalRow> visitProviderFailure(long tenantId, UUID outboxId) {
        return visitProviderFailures(tenantId, outboxId, 1).stream().findFirst();
    }

    private List<OperationalRow> visitProviderFailures(
            long tenantId, UUID outboxId, int limit) {
        return jdbc.query("""
                SELECT outbox.outbox_id AS exception_id, outbox.visit_id AS parent_id,
                       outbox.operation_type AS operation, outbox.delivery_state AS state,
                       COALESCE(outbox.evidence_reference,
                                'VISIT_PROVIDER_RESULT_UNKNOWN') AS error_code,
                       outbox.updated_at AS detected_at, visit.version,
                       outbox.attempt_count, visit.visit_state AS detail,
                       NULL::uuid AS site_id,NULL::uuid AS resource_id
                  FROM wp_visit_outbox outbox
                  JOIN wp_visits visit
                    ON visit.tenant_id=outbox.tenant_id AND visit.visit_id=outbox.visit_id
                 WHERE outbox.tenant_id=?
                   AND (CAST(? AS uuid) IS NULL OR outbox.outbox_id=?)
                   AND (outbox.delivery_state IN ('RESULT_UNKNOWN','DEAD_LETTER')
                        OR (outbox.delivery_state='RETRY' AND outbox.attempt_count>=10))
                 ORDER BY outbox.updated_at DESC,outbox.outbox_id
                 LIMIT ?
                """, this::operationalRow, tenantId, outboxId, outboxId, bounded(limit));
    }

    public List<OperationalRow> accessPassFailures(long tenantId) {
        return accessPassFailures(tenantId, null, 100);
    }

    public List<OperationalRow> accessPassFailures(long tenantId, int limit) {
        return accessPassFailures(tenantId, null, limit);
    }

    public java.util.Optional<OperationalRow> accessPassFailure(long tenantId, UUID exceptionId) {
        return accessPassFailures(tenantId, exceptionId, 1).stream().findFirst();
    }

    private List<OperationalRow> accessPassFailures(
            long tenantId, UUID exceptionId, int limit) {
        return jdbc.query("""
                SELECT command.command_id AS exception_id, command.pass_id AS parent_id,
                       command.command_type AS operation, command.command_state AS state,
                       COALESCE(command.result_code,'ACCESS_PASS_RESULT_UNKNOWN') AS error_code,
                       command.updated_at AS detected_at, command.version,
                       0 AS attempt_count, 'COMMAND' AS detail,
                       pass.site_id,pass.resource_id
                  FROM wp_navigation_access_pass_commands command
                  JOIN wp_navigation_access_passes pass
                    ON pass.tenant_id=command.tenant_id AND pass.pass_id=command.pass_id
                 WHERE command.tenant_id=? AND command.command_state='RESULT_UNKNOWN'
                   AND (CAST(? AS uuid) IS NULL OR command.command_id=?)
                UNION ALL
                SELECT pass.pass_id AS exception_id, pass.pass_id AS parent_id,
                       'PAIR' AS operation, 'PAIRING_RETRY_EXHAUSTED' AS state,
                       'ACCESS_PASS_PAIRING_RETRY_EXHAUSTED' AS error_code,
                       pass.pairing_locked_at AS detected_at, pass.version,
                       pass.pairing_attempt_count AS attempt_count, 'PAIRING' AS detail,
                       pass.site_id,pass.resource_id
                  FROM wp_navigation_access_passes pass
                 WHERE pass.tenant_id=? AND pass.pairing_locked_at IS NOT NULL
                   AND pass.pairing_consumed_at IS NULL
                   AND pass.lifecycle_state='ACTIVE'
                   AND pass.expires_at>CURRENT_TIMESTAMP
                   AND (CAST(? AS uuid) IS NULL OR pass.pass_id=?)
                 ORDER BY detected_at DESC,exception_id
                 LIMIT ?
                """, this::operationalRow, tenantId, exceptionId, exceptionId,
                tenantId, exceptionId, exceptionId, bounded(limit));
    }

    public List<ConnectorRow> connectorFailures(long tenantId) {
        return connectorFailures(tenantId, null, 100);
    }

    public List<ConnectorRow> connectorFailures(long tenantId, int limit) {
        return connectorFailures(tenantId, null, limit);
    }

    public java.util.Optional<ConnectorRow> connectorFailure(long tenantId, String kind) {
        return connectorFailures(tenantId, kind, 1).stream().findFirst();
    }

    private List<ConnectorRow> connectorFailures(long tenantId, String kind, int limit) {
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
                   AND (CAST(? AS varchar) IS NULL OR config.connector_kind=?)
                   AND (truth.observation_id IS NULL
                        OR truth.reported_state <> 'HEALTHY'
                        OR truth.received_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                        OR COALESCE(truth.dead_letter_queue_depth, 0) > 0)
                 ORDER BY COALESCE(truth.received_at, config.updated_at) DESC,
                          config.connector_kind
                 LIMIT ?
                """, (rs, row) -> new ConnectorRow(
                rs.getString("connector_kind"), rs.getString("provider"), rs.getBoolean("enabled"),
                rs.getLong("configuration_version"), rs.getString("reported_state"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                nullableLong(rs, "lag_seconds"), nullableLong(rs, "retry_queue_depth"),
                nullableLong(rs, "dead_letter_queue_depth"), rs.getString("error_code"),
                nullableLong(rs, "runtime_version"),
                rs.getObject("replay_job_id", UUID.class), rs.getString("replay_state")),
                tenantId, kind, kind, bounded(limit));
    }

    public SourceCounts unresolvedCounts(long tenantId) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM wp_safety_incidents
                    WHERE tenant_id=? AND incident_state IN ('ACTIVE','CLOSURE_PENDING')) safety,
                  (SELECT COUNT(*) FROM wp_booking_batch_items
                    WHERE tenant_id=? AND item_state IN
                      ('FAILED','RESULT_UNKNOWN','COMPENSATION_FAILED')) booking,
                  (SELECT COUNT(*)
                     FROM wp_experience_connector_configurations config
                     LEFT JOIN wp_connector_runtime_truth truth
                       ON truth.tenant_id=config.tenant_id
                      AND truth.connector_kind=config.connector_kind
                    WHERE config.tenant_id=? AND config.enabled=TRUE
                      AND (truth.observation_id IS NULL OR truth.reported_state<>'HEALTHY'
                           OR truth.received_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes'
                           OR COALESCE(truth.dead_letter_queue_depth,0)>0)) connector,
                  (SELECT COUNT(*)
                     FROM wp_navigation_device_commands command
                     LEFT JOIN LATERAL (
                       SELECT delivery_state,attempt_count
                         FROM wp_navigation_device_outbox
                        WHERE tenant_id=command.tenant_id AND command_id=command.command_id
                        ORDER BY updated_at DESC,outbox_id DESC LIMIT 1
                     ) outbox ON TRUE
                    WHERE command.tenant_id=?
                      AND (command.command_state='RESULT_UNKNOWN'
                           OR outbox.delivery_state='DEAD_LETTER'
                           OR (outbox.delivery_state='RESULT_UNKNOWN'
                               AND outbox.attempt_count>=10))) device_command,
                  (SELECT COUNT(*) FROM wp_service_orders orders
                    WHERE orders.tenant_id=?
                      AND (orders.order_state='RESULT_UNKNOWN'
                           OR EXISTS (SELECT 1 FROM wp_service_order_lines line
                             WHERE line.tenant_id=orders.tenant_id
                               AND line.service_order_id=orders.service_order_id
                               AND line.line_state='RESULT_UNKNOWN')
                           OR EXISTS (SELECT 1 FROM wp_service_fulfillment_tasks task
                             WHERE task.tenant_id=orders.tenant_id
                               AND task.service_order_id=orders.service_order_id
                               AND task.task_state='RESULT_UNKNOWN'))) service_order,
                  (SELECT COUNT(*) FROM wp_service_provider_truth
                    WHERE tenant_id=? AND configured=TRUE
                      AND (reported_state IS NULL OR reported_state<>'HEALTHY'
                           OR received_at IS NULL
                           OR received_at<CURRENT_TIMESTAMP-INTERVAL '5 minutes'
                           OR observed_configuration_version IS DISTINCT FROM
                              configuration_version)) service_provider,
                  (SELECT COUNT(*) FROM wp_service_line_adjustments adjustment
                    WHERE adjustment.tenant_id=?
                      AND (adjustment.adjustment_state IN
                            ('RECONCILIATION_PENDING','RESULT_UNKNOWN',
                             'REFUND_NOT_CONFIGURED','FAILED')
                           OR (adjustment.adjustment_state='CANCELLATION_PENDING'
                               AND adjustment.provider_recovery_attempt_count>=10))) service_refund,
                  (SELECT COUNT(*) FROM wp_visit_outbox
                    WHERE tenant_id=?
                      AND (delivery_state IN ('RESULT_UNKNOWN','DEAD_LETTER')
                           OR (delivery_state='RETRY' AND attempt_count>=10))) visit_provider,
                  ((SELECT COUNT(*) FROM wp_navigation_access_pass_commands
                     WHERE tenant_id=? AND command_state='RESULT_UNKNOWN')
                   +
                   (SELECT COUNT(*) FROM wp_navigation_access_passes
                     WHERE tenant_id=? AND pairing_locked_at IS NOT NULL
                       AND pairing_consumed_at IS NULL AND lifecycle_state='ACTIVE'
                       AND expires_at>CURRENT_TIMESTAMP)) access_pass
                """, (rs, row) -> new SourceCounts(
                rs.getLong("safety"), rs.getLong("booking"), rs.getLong("connector"),
                rs.getLong("device_command"), rs.getLong("service_order"),
                rs.getLong("service_provider"), rs.getLong("service_refund"),
                rs.getLong("visit_provider"), rs.getLong("access_pass")),
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId,
                tenantId, tenantId, tenantId, tenantId);
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

    private static int bounded(int limit) {
        return Math.max(1, Math.min(limit, 251));
    }

    private OperationalRow operationalRow(java.sql.ResultSet rs, int row)
            throws java.sql.SQLException {
        return new OperationalRow(
                rs.getObject("exception_id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getString("operation"),
                rs.getString("state"), rs.getString("error_code"),
                rs.getObject("detected_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getInt("attempt_count"), rs.getString("detail"),
                rs.getObject("site_id", UUID.class), rs.getObject("resource_id", UUID.class));
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
    public record OperationalRow(UUID id, UUID parentId, String operation, String state,
                                 String errorCode, OffsetDateTime detectedAt, long version,
                                 int attemptCount, String detail, UUID siteId, UUID resourceId) { }
    public record ServiceProviderRow(String providerCode, String state, String errorCode,
                                     OffsetDateTime receivedAt, OffsetDateTime updatedAt,
                                     long configurationVersion, Long observedConfigurationVersion,
                                     long version) { }
    public record SourceCounts(long safety, long booking, long connector, long deviceCommand,
                               long serviceOrder, long serviceProvider, long serviceRefund,
                               long visitProvider, long accessPass) {
        long get(WorkplaceExceptionConsoleDtos.ExceptionSource source) {
            return switch (source) {
                case SAFETY -> safety;
                case BOOKING -> booking;
                case CONNECTOR -> connector;
                case DEVICE_COMMAND -> deviceCommand;
                case SERVICE_ORDER -> serviceOrder;
                case SERVICE_PROVIDER -> serviceProvider;
                case SERVICE_REFUND -> serviceRefund;
                case VISIT_PROVIDER -> visitProvider;
                case ACCESS_PASS -> accessPass;
            };
        }

        long total() {
            return safety + booking + connector + deviceCommand + serviceOrder
                    + serviceProvider + serviceRefund + visitProvider + accessPass;
        }
    }
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
