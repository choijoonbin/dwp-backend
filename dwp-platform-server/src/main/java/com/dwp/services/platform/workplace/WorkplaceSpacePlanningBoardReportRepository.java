package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpacePlanningBoardReportDtos.*;

@Repository
public class WorkplaceSpacePlanningBoardReportRepository {
    private final JdbcTemplate jdbc;

    public WorkplaceSpacePlanningBoardReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockPreviewKey(long tenantId, long actorId, String idempotencyKey) {
        advisoryLock("space-planning-report-preview", tenantId, actorId, idempotencyKey);
    }

    public void lockCommandKey(long tenantId, long actorId, String idempotencyKey) {
        advisoryLock("space-planning-report-command", tenantId, actorId, idempotencyKey);
    }

    private void advisoryLock(String namespace, long tenantId, long actorId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        namespace + ":" + tenantId + ":" + actorId + ":" + key),
                result -> null);
    }

    public ReportSource lockReportSource(long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT planning.scenario_id,planning.version AS scenario_version,
                       planning.name AS scenario_name,planning.lifecycle_state,
                       planning.site_id,site.site_code,
                       COALESCE(NULLIF(site.name_en,''),site.name_ko) AS site_name,
                       planning.floor_id,
                       CASE WHEN planning.floor_id IS NULL THEN NULL
                            ELSE COALESCE(NULLIF(floor.name_en,''),floor.name_ko) END AS floor_name,
                       planning.window_start,planning.window_end,
                       planning.proposed_capacity,planning.proposed_room_capacity,
                       planning.proposed_accessible_resource_count,
                       jsonb_array_length(planning.affected_resource_ids) AS affected_resource_count,
                       preview.comparison::text AS comparison,
                       preview.forecast_projection::text AS forecast_projection,
                       preview.emission_projection::text AS emission_projection,
                       (SELECT impact.impacted_booking_count
                          FROM wp_space_planning_booking_impact_previews impact
                         WHERE impact.tenant_id=planning.tenant_id
                           AND impact.scenario_id=planning.scenario_id
                         ORDER BY impact.created_at DESC,impact.impact_preview_id DESC
                         LIMIT 1) AS impacted_booking_count
                  FROM wp_space_planning_scenarios planning
                  JOIN wp_sites site
                    ON site.tenant_id=planning.tenant_id AND site.site_id=planning.site_id
                  LEFT JOIN wp_floors floor
                    ON floor.tenant_id=planning.tenant_id AND floor.site_id=planning.site_id
                   AND floor.floor_id=planning.floor_id
                  LEFT JOIN wp_space_planning_scenario_previews preview
                    ON preview.tenant_id=planning.tenant_id
                   AND preview.preview_id=planning.active_preview_id
                 WHERE planning.tenant_id=? AND planning.scenario_id=?
                 FOR UPDATE OF planning
                """, this::reportSource, tenantId, scenarioId).stream().findFirst().orElse(null);
    }

    public PreviewRow previewByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_report_previews
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, this::preview, tenantId, actorId, idempotencyKey)
                .stream().findFirst().orElse(null);
    }

    public PreviewRow lockPreview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_space_planning_report_previews
                 WHERE tenant_id=? AND actor_user_id=? AND preview_id=?
                 FOR UPDATE
                """, this::preview, tenantId, actorId, previewId)
                .stream().findFirst().orElse(null);
    }

    public void insertPreview(PreviewRow row) {
        jdbc.update("""
                INSERT INTO wp_space_planning_report_previews(
                    preview_id,tenant_id,actor_user_id,scenario_id,site_id,floor_id,
                    report_format,scenario_version,preview_version,report_snapshot,
                    snapshot_sha256,confirmation_token,reason,idempotency_key,
                    request_fingerprint,correlation_id,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?)
                """, row.previewId(), row.tenantId(), row.actorId(), row.scenarioId(),
                row.siteId(), row.floorId(), row.format().name(), row.scenarioVersion(),
                row.previewVersion(), row.snapshotJson(), row.snapshotSha256(),
                row.confirmationToken(), row.reason(), row.idempotencyKey(),
                row.requestFingerprint(), row.correlationId(), row.expiresAt(), row.createdAt());
    }

    public CommandRow commandByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return commandQuery("""
                 WHERE command.tenant_id=? AND command.actor_user_id=?
                   AND command.idempotency_key=?
                """, tenantId, actorId, idempotencyKey);
    }

    public CommandRow command(long tenantId, long actorId, UUID commandId) {
        return commandQuery("""
                 WHERE command.tenant_id=? AND command.actor_user_id=?
                   AND command.command_id=?
                """, tenantId, actorId, commandId);
    }

    private CommandRow commandQuery(String predicate, Object... arguments) {
        return jdbc.query("""
                SELECT command.*,preview.site_id,preview.floor_id
                  FROM wp_space_planning_report_commands command
                  JOIN wp_space_planning_report_previews preview
                    ON preview.tenant_id=command.tenant_id
                   AND preview.preview_id=command.preview_id
                """ + predicate, this::command, arguments)
                .stream().findFirst().orElse(null);
    }

    public void insertCommand(CommandRow row) {
        jdbc.update("""
                INSERT INTO wp_space_planning_report_commands(
                    command_id,tenant_id,actor_user_id,preview_id,scenario_id,report_format,
                    scenario_version,expected_preview_version,command_state,command_version,
                    reason,explicit_confirmation,decision_revision,document_mime,file_name,
                    document_content,document_size,content_sha256,idempotency_key,
                    request_fingerprint,correlation_id,accepted_at,completed_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.commandId(), row.tenantId(), row.actorId(), row.previewId(),
                row.scenarioId(), row.format().name(), row.scenarioVersion(),
                row.expectedPreviewVersion(), row.state().name(), row.commandVersion(),
                row.reason(), true, row.decisionRevision(), row.mimeType(), row.fileName(),
                row.documentContent(), row.documentSize(), row.contentSha256(),
                row.idempotencyKey(), row.requestFingerprint(), row.correlationId(),
                row.acceptedAt(), row.completedAt(), row.expiresAt());
    }

    public void audit(
            long tenantId,
            long actorId,
            UUID scenarioId,
            UUID previewId,
            UUID commandId,
            String eventType,
            String correlationId,
            String evidenceJson,
            OffsetDateTime occurredAt) {
        jdbc.update("""
                INSERT INTO wp_space_planning_report_audit_events(
                    report_audit_event_id,tenant_id,actor_user_id,scenario_id,preview_id,
                    command_id,event_type,correlation_id,evidence,occurred_at)
                VALUES(?,?,?,?,?,?,?, ?,?::jsonb,?)
                """, UUID.randomUUID(), tenantId, actorId, scenarioId, previewId, commandId,
                eventType, correlationId, evidenceJson, occurredAt);
    }

    private ReportSource reportSource(ResultSet rs, int rowNumber) throws SQLException {
        return new ReportSource(
                rs.getObject("scenario_id", UUID.class), rs.getLong("scenario_version"),
                rs.getString("scenario_name"), rs.getString("lifecycle_state"),
                rs.getObject("site_id", UUID.class), rs.getString("site_code"),
                rs.getString("site_name"), rs.getObject("floor_id", UUID.class),
                rs.getString("floor_name"), rs.getObject("window_start", OffsetDateTime.class),
                rs.getObject("window_end", OffsetDateTime.class),
                rs.getInt("proposed_capacity"), rs.getInt("proposed_room_capacity"),
                rs.getInt("proposed_accessible_resource_count"),
                rs.getInt("affected_resource_count"), rs.getString("comparison"),
                rs.getString("forecast_projection"), rs.getString("emission_projection"),
                nullableInteger(rs, "impacted_booking_count"));
    }

    private PreviewRow preview(ResultSet rs, int rowNumber) throws SQLException {
        return new PreviewRow(
                rs.getObject("preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("scenario_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class),
                ReportFormat.valueOf(rs.getString("report_format")),
                rs.getLong("scenario_version"), rs.getLong("preview_version"),
                rs.getString("report_snapshot"), rs.getString("snapshot_sha256"),
                rs.getString("confirmation_token"), rs.getString("reason"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("correlation_id"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private CommandRow command(ResultSet rs, int rowNumber) throws SQLException {
        return new CommandRow(
                rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("preview_id", UUID.class),
                rs.getObject("scenario_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class),
                ReportFormat.valueOf(rs.getString("report_format")),
                rs.getLong("scenario_version"), rs.getLong("expected_preview_version"),
                ReportCommandState.valueOf(rs.getString("command_state")),
                rs.getLong("command_version"), rs.getString("reason"),
                rs.getString("decision_revision"), rs.getString("document_mime"),
                rs.getString("file_name"), rs.getBytes("document_content"),
                rs.getLong("document_size"), rs.getString("content_sha256"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getString("correlation_id"), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public record ReportSource(
            UUID scenarioId,
            long scenarioVersion,
            String scenarioName,
            String scenarioState,
            UUID siteId,
            String siteCode,
            String siteName,
            UUID floorId,
            String floorName,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            int proposedCapacity,
            int proposedRoomCapacity,
            int proposedAccessibleResourceCount,
            int affectedResourceCount,
            String comparisonJson,
            String forecastJson,
            String emissionJson,
            Integer impactedBookingCount) { }

    public record PreviewRow(
            UUID previewId,
            long tenantId,
            long actorId,
            UUID scenarioId,
            UUID siteId,
            UUID floorId,
            ReportFormat format,
            long scenarioVersion,
            long previewVersion,
            String snapshotJson,
            String snapshotSha256,
            String confirmationToken,
            String reason,
            String idempotencyKey,
            String requestFingerprint,
            String correlationId,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) { }

    public record CommandRow(
            UUID commandId,
            long tenantId,
            long actorId,
            UUID previewId,
            UUID scenarioId,
            UUID siteId,
            UUID floorId,
            ReportFormat format,
            long scenarioVersion,
            long expectedPreviewVersion,
            ReportCommandState state,
            long commandVersion,
            String reason,
            String decisionRevision,
            String mimeType,
            String fileName,
            byte[] documentContent,
            long documentSize,
            String contentSha256,
            String idempotencyKey,
            String requestFingerprint,
            String correlationId,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime expiresAt) {
        public CommandRow {
            documentContent = documentContent == null ? new byte[0] : documentContent.clone();
        }

        @Override
        public byte[] documentContent() {
            return documentContent.clone();
        }
    }
}
