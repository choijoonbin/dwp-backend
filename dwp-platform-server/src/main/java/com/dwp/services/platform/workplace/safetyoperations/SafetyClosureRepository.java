package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Repository
public class SafetyClosureRepository extends SafetyRepositorySupport {
    public SafetyClosureRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    ClosureCounts closureCounts(long tenantId, UUID incidentId, UUID snapshotId) {
        return jdbc.queryForObject("""
                SELECT
                  COUNT(*) FILTER (WHERE r.response_state='NEEDS_HELP') needs_help,
                  COUNT(*) FILTER (WHERE m.subject_user_id IS NOT NULL
                                      AND r.response_state IS NULL) no_response,
                  COALESCE((SELECT COUNT(*) FROM wp_safety_dispatch_attempts a
                    JOIN wp_safety_dispatch_batches b ON b.tenant_id=a.tenant_id
                     AND b.dispatch_batch_id=a.dispatch_batch_id
                   WHERE b.tenant_id=? AND b.incident_id=? AND a.attempt_state='DELIVERED'),0) delivered,
                  COALESCE((SELECT COUNT(*) FROM wp_safety_dispatch_attempts a
                    JOIN wp_safety_dispatch_batches b ON b.tenant_id=a.tenant_id
                     AND b.dispatch_batch_id=a.dispatch_batch_id
                   WHERE b.tenant_id=? AND b.incident_id=?
                     AND a.attempt_state IN ('DELIVERY_FAILED','RESULT_UNKNOWN','OFFLINE_QUEUED')),0) failed_unknown
                  FROM wp_safety_audience_members m
                  LEFT JOIN wp_safety_responses r
                    ON r.tenant_id=m.tenant_id AND r.incident_id=?
                   AND r.subject_user_id=m.subject_user_id
                 WHERE m.tenant_id=? AND m.audience_snapshot_id=? AND m.included=TRUE
                """, (rs, n) -> new ClosureCounts(rs.getInt(1), rs.getInt(2),
                rs.getInt(3), rs.getInt(4)), tenantId, incidentId, tenantId, incidentId,
                incidentId, tenantId, snapshotId);
    }

    ClosurePreview insertClosurePreview(
            long tenantId, long actorId, UUID incidentId, UUID previewId, UUID commandId, long version,
            ClosureCounts counts, boolean eligible, java.util.List<String> warnings,
            OffsetDateTime expiresAt, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_closure_previews(
                    closure_preview_id,tenant_id,incident_id,command_id,actor_user_id,incident_version,
                    needs_help_count,no_response_count,delivered_count,failed_or_unknown_count,
                    eligible,warnings,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                """, previewId, tenantId, incidentId, commandId, actorId, version, counts.needsHelp(),
                counts.noResponse(), counts.delivered(), counts.failedUnknown(), eligible,
                json(warnings), expiresAt, now);
        return new ClosurePreview(previewId, incidentId, version, counts.needsHelp(),
                counts.noResponse(), counts.delivered(), counts.failedUnknown(), eligible,
                warnings, expiresAt, now);
    }

    Optional<ClosurePreview> closurePreviewByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_closure_previews WHERE tenant_id=? AND command_id=?
                """, this::closurePreviewRow, tenantId, commandId).stream().findFirst();
    }

    Optional<ClosurePreview> closurePreview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_closure_previews
                 WHERE tenant_id=? AND actor_user_id=? AND closure_preview_id=?
                """, this::closurePreviewRow, tenantId, actorId, previewId)
                .stream().findFirst();
    }

    Optional<ClosurePreview> closurePreview(long tenantId, UUID incidentId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_closure_previews
                 WHERE tenant_id=? AND incident_id=? AND closure_preview_id=?
                """, this::closurePreviewRow, tenantId, incidentId, previewId)
                .stream().findFirst();
    }

    private ClosurePreview closurePreviewRow(ResultSet rs, int row) throws SQLException {
        return new ClosurePreview(rs.getObject("closure_preview_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getLong("incident_version"),
                rs.getInt("needs_help_count"), rs.getInt("no_response_count"),
                rs.getInt("delivered_count"), rs.getInt("failed_or_unknown_count"),
                rs.getBoolean("eligible"), list(rs.getString("warnings"), String.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    Optional<ClosureRow> pendingClosure(long tenantId, UUID incidentId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_closure_requests
                 WHERE tenant_id=? AND incident_id=? AND request_state='PENDING_APPROVAL'
                """, this::closureRow, tenantId, incidentId).stream().findFirst();
    }

    Optional<ClosureRow> closure(long tenantId, UUID incidentId, UUID requestId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_closure_requests
                 WHERE tenant_id=? AND incident_id=? AND closure_request_id=?
                """, this::closureRow, tenantId, incidentId, requestId).stream().findFirst();
    }

    Optional<ClosureRow> closureByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_closure_requests WHERE tenant_id=? AND command_id=?
                """, this::closureRow, tenantId, commandId).stream().findFirst();
    }

    Optional<ClosureRow> insertClosure(
            long tenantId, long actorId, UUID incidentId, UUID commandId, ClosurePreview preview,
            ClosureRequestInput request, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        int incident = jdbc.update("""
                UPDATE wp_safety_incidents SET incident_state='CLOSURE_PENDING',
                       version=version+1,updated_at=?
                 WHERE tenant_id=? AND incident_id=? AND version=? AND incident_state='ACTIVE'
                """, now, tenantId, incidentId, request.expectedIncidentVersion());
        if (incident == 0) return Optional.empty();
        jdbc.update("""
                INSERT INTO wp_safety_closure_requests(
                    closure_request_id,tenant_id,incident_id,command_id,closure_preview_id,requested_by,
                    designated_approver_id,closure_reason,follow_up_actions,request_state,
                    version,requested_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,'PENDING_APPROVAL',1,?,?)
                """, id, tenantId, incidentId, commandId, preview.closurePreviewId(), actorId,
                request.designatedApproverId(), request.closureReason(),
                request.followUpActions(), now, now);
        return closure(tenantId, incidentId, id);
    }

    boolean approveClosure(long tenantId, UUID incidentId, ClosureRow closure,
                           long approverId, ClosureApprovalInput request,
                           OffsetDateTime now) {
        if (closure.designatedApproverId() != approverId
                || closure.version() != request.expectedClosureVersion()) return false;
        int incident = jdbc.update("""
                UPDATE wp_safety_incidents SET incident_state=?,closed_at=?,
                       version=version+1,updated_at=?
                 WHERE tenant_id=? AND incident_id=? AND version=?
                   AND incident_state='CLOSURE_PENDING'
                """, request.approved() ? "CLOSED" : "ACTIVE",
                request.approved() ? now : null, now, tenantId, incidentId,
                request.expectedIncidentVersion());
        if (incident == 0) return false;
        jdbc.update("""
                UPDATE wp_safety_closure_requests SET request_state=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND closure_request_id=? AND version=?
                """, request.approved() ? "APPROVED" : "REJECTED", now,
                tenantId, closure.id(), closure.version());
        jdbc.update("""
                INSERT INTO wp_safety_closure_approvals(
                    closure_approval_id,tenant_id,closure_request_id,approver_user_id,
                    approved,approval_reason,approved_at) VALUES(?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, closure.id(), approverId,
                request.approved(), request.approvalReason(), now);
        return true;
    }

    PostIncidentReport createReport(long tenantId, UUID incidentId, long actorId,
                                    java.util.Map<String, Object> summary,
                                    OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_safety_post_incident_reports(
                    report_id,tenant_id,incident_id,summary,generated_at,generated_by,version)
                VALUES(?,?,?,?::jsonb,?,?,1)
                ON CONFLICT (tenant_id,incident_id) DO NOTHING
                """, id, tenantId, incidentId, json(summary), now, actorId);
        return report(tenantId, incidentId).orElseThrow();
    }

    Optional<PostIncidentReport> report(long tenantId, UUID incidentId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_post_incident_reports
                 WHERE tenant_id=? AND incident_id=?
                """, (rs, n) -> new PostIncidentReport(rs.getObject("report_id", UUID.class),
                incidentId, map(rs.getString("summary")), rs.getLong("version"),
                rs.getObject("generated_at", OffsetDateTime.class)), tenantId, incidentId)
                .stream().findFirst();
    }

    GuardedExport insertExport(
            long tenantId, UUID incidentId, UUID commandId, long actorId, ExportFormat format,
            String purpose, String reason, String correlationId, String stepUpEvidence,
            byte[] payload, OffsetDateTime now,
            OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        String contentType = format == ExportFormat.PDF
                ? "application/pdf" : "text/csv;charset=UTF-8";
        String hash = sha256(payload);
        jdbc.update("""
                INSERT INTO wp_safety_guarded_exports(
                    guarded_export_id,tenant_id,incident_id,command_id,requested_by,export_format,
                    purpose,reason,correlation_id,step_up_evidence,content_type,content_sha256,
                    payload,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, tenantId, incidentId, commandId, actorId, format.name(), purpose, reason,
                correlationId, stepUpEvidence, contentType, hash, payload, now, expiresAt);
        return new GuardedExport(id, incidentId, format, purpose, reason, actorId,
                correlationId, stepUpEvidence, contentType, hash,
                payload.length, "/v1/admin/workplace/safety/exports/" + id + "/content",
                now, expiresAt);
    }

    Optional<GuardedExport> exportByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_guarded_exports WHERE tenant_id=? AND command_id=?
                """, (rs, n) -> new GuardedExport(
                rs.getObject("guarded_export_id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                ExportFormat.valueOf(rs.getString("export_format")), rs.getString("purpose"),
                rs.getString("reason"), rs.getLong("requested_by"),
                rs.getString("correlation_id"), rs.getString("step_up_evidence"),
                rs.getString("content_type"), rs.getString("content_sha256"),
                rs.getBytes("payload").length,
                "/v1/admin/workplace/safety/exports/"
                        + rs.getObject("guarded_export_id", UUID.class) + "/content",
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)), tenantId, commandId)
                .stream().findFirst();
    }

    Optional<ExportContent> export(long tenantId, UUID exportId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT * FROM wp_safety_guarded_exports
                 WHERE tenant_id=? AND guarded_export_id=? AND expires_at>?
                """, (rs, n) -> {
            GuardedExport metadata = new GuardedExport(exportId,
                    rs.getObject("incident_id", UUID.class),
                    ExportFormat.valueOf(rs.getString("export_format")),
                    rs.getString("purpose"), rs.getString("reason"), rs.getLong("requested_by"),
                    rs.getString("correlation_id"), rs.getString("step_up_evidence"),
                    rs.getString("content_type"),
                    rs.getString("content_sha256"), rs.getBytes("payload").length,
                    "/v1/admin/workplace/safety/exports/" + exportId + "/content",
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("expires_at", OffsetDateTime.class));
            return new ExportContent(metadata, rs.getBytes("payload"));
        }, tenantId, exportId, now).stream().findFirst();
    }

    private ClosureRow closureRow(ResultSet rs, int row) throws SQLException {
        return new ClosureRow(rs.getObject("closure_request_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getLong("requested_by"),
                rs.getLong("designated_approver_id"), rs.getString("closure_reason"),
                rs.getString("follow_up_actions"), rs.getString("request_state"),
                rs.getLong("version"), rs.getObject("requested_at", OffsetDateTime.class));
    }

    record ClosureCounts(int needsHelp, int noResponse, int delivered, int failedUnknown) { }
    record ClosureRow(UUID id, UUID incidentId, long requestedBy, long designatedApproverId,
                      String reason, String followUpActions, String state, long version,
                      OffsetDateTime requestedAt) { }
}
