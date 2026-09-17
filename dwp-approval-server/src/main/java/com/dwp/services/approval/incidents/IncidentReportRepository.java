package com.dwp.services.approval.incidents;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Repository
class IncidentReportRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    private final IncidentRepository incidents;

    IncidentReportRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical,
            IncidentRepository incidents) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.incidents = incidents;
    }

    IncidentReport create(
            Context context,
            UUID incidentId,
            ReportCommand command,
            Map<String, Object> payload,
            Instant now) {
        IncidentView incident = incidents.requireIncident(context, incidentId, true);
        if (incident.version() != command.expectedIncidentVersion()
                || !List.of(IncidentStatus.RESOLVED, IncidentStatus.CLOSED)
                .contains(incident.status())) {
            throw IncidentRejected.conflict(
                    "An incident report requires the exact version of a resolved incident.");
        }
        String digest = canonical.fingerprint(payload);
        MapSqlParameterSource values = base(context)
                .addValue("incident", incidentId)
                .addValue("report", command.reportId())
                .addValue("expected", command.expectedIncidentVersion())
                .addValue("format", command.format().name())
                .addValue("payload", canonical.json(payload))
                .addValue("digest", digest)
                .addValue("now", Timestamp.from(now));
        int updated = jdbc.update("""
                UPDATE apr_incidents
                   SET version=version+1,updated_by=:actor,updated_at=:now
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:incident AND version=:expected
                   AND status IN ('RESOLVED','CLOSED')
                """, values);
        if (updated != 1) {
            throw IncidentRejected.conflict(
                    "Incident state changed before the evidence report was sealed.");
        }
        int inserted = jdbc.update("""
                INSERT INTO apr_incident_reports(
                    report_id,tenant_id,resource_set_key,incident_id,incident_version,
                    report_format,report_payload,report_sha256,generated_by,generated_at)
                VALUES(:report,:tenant,:scope,:incident,:expected + 1,:format,
                       CAST(:payload AS jsonb),:digest,:actor,:now)
                """, values);
        if (inserted != 1) {
            throw IncidentRejected.conflict("Incident report insertion lost its exact fence.");
        }
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence),0)+1 FROM apr_incident_timeline
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:incident
                """, values, Long.class);
        jdbc.update("""
                INSERT INTO apr_incident_timeline(
                    tenant_id,resource_set_key,incident_id,sequence,event_type,
                    summary,evidence_sha256,actor_user_id,occurred_at)
                VALUES(:tenant,:scope,:incident,:sequence,'REPORT_CREATED',
                       'Incident evidence report sealed',:digest,:actor,:now)
                """, values.addValue("sequence", sequence));
        return new IncidentReport(command.reportId(), incidentId,
                command.expectedIncidentVersion() + 1, command.format(),
                Map.copyOf(payload), digest, context.actorUserId(), now);
    }

    List<IncidentReport> reports(Context context, UUID incidentId) {
        incidents.requireIncident(context, incidentId, false);
        return jdbc.query("""
                SELECT report_id,incident_version,report_format,report_payload::text,
                       report_sha256,generated_by,generated_at
                  FROM apr_incident_reports
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:incident
                 ORDER BY generated_at DESC,report_id DESC
                """, base(context).addValue("incident", incidentId),
                (result, row) -> report(incidentId, result));
    }

    IncidentReport report(Context context, UUID incidentId, UUID reportId) {
        incidents.requireIncident(context, incidentId, false);
        List<IncidentReport> values = jdbc.query("""
                SELECT report_id,incident_version,report_format,report_payload::text,
                       report_sha256,generated_by,generated_at
                  FROM apr_incident_reports
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND incident_id=:incident AND report_id=:report
                """, base(context).addValue("incident", incidentId)
                .addValue("report", reportId), (result, row) -> report(incidentId, result));
        if (values.size() != 1) {
            throw IncidentRejected.invalid("Incident report was not found in this scope.");
        }
        return values.getFirst();
    }

    List<DeadLetterView> deadLetters(Context context, List<UUID> selected, int limit) {
        requireActiveTenant(context);
        if (limit < 1 || limit > 200) {
            throw IncidentRejected.invalid("Dead-letter limit must be between 1 and 200.");
        }
        MapSqlParameterSource values = base(context).addValue("limit", limit);
        String selection = "";
        if (selected != null && !selected.isEmpty()) {
            values.addValue("selected", selected);
            selection = " AND outbox_id IN (:selected)";
        }
        return jdbc.query("""
                SELECT outbox_id,event_id,request_id,event_type,status,attempt_count,
                       manual_retry_count,version,recovery_auditor_assignment_state,
                       last_error,available_at,locked_until,updated_at
                  FROM apr_integration_outbox
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND (status='DEAD' OR recovery_auditor_assignment_state='EXHAUSTED')
                """ + selection + " ORDER BY updated_at DESC,outbox_id LIMIT :limit",
                values, this::deadLetter);
    }

    private IncidentReport report(UUID incidentId, ResultSet result) throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = canonical.read(
                result.getString("report_payload"), Map.class);
        return new IncidentReport(result.getObject("report_id", UUID.class), incidentId,
                result.getLong("incident_version"),
                ReportFormat.valueOf(result.getString("report_format")),
                Map.copyOf(payload), result.getString("report_sha256"),
                result.getLong("generated_by"),
                result.getTimestamp("generated_at").toInstant());
    }

    private DeadLetterView deadLetter(ResultSet result, int row) throws SQLException {
        String error = result.getString("last_error");
        UUID outboxId = result.getObject("outbox_id", UUID.class);
        return new DeadLetterView(outboxId, result.getObject("event_id", UUID.class),
                result.getObject("request_id", UUID.class), result.getString("event_type"),
                result.getString("status"), result.getInt("attempt_count"),
                result.getInt("manual_retry_count"), result.getLong("version"),
                result.getString("recovery_auditor_assignment_state"),
                error == null ? null : ApprovalDocumentCanonical.sha(error),
                instant(result, "available_at"), instant(result, "locked_until"),
                instant(result, "updated_at"),
                "/v1/admin/operations/events/" + outboxId + "/replay");
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) {
            throw IncidentRejected.forbidden("Approval tenant is not active.");
        }
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource()
                .addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId());
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
