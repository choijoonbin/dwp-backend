package com.dwp.services.approval.incidents;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Repository
public class IncidentProjectionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    private final IncidentRepository commands;

    public IncidentProjectionRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical,
            IncidentRepository commands) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.commands = commands;
    }

    List<IncidentView> incidents(Context context) {
        requireActiveTenant(context);
        return jdbc.query("""
                SELECT incident_id FROM apr_incidents
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY updated_at DESC,incident_id
                """, base(context), (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> commands.requireIncident(context, id, false)).toList();
    }

    IncidentDetail detail(Context context, UUID incidentId) {
        IncidentView incident = commands.requireIncident(context, incidentId, false);
        return new IncidentDetail(incident, timeline(context, incidentId),
                diagnostics(context, incidentId), plans(context, incidentId),
                postmortem(context, incidentId));
    }

    private List<TimelineEntry> timeline(Context context, UUID incidentId) {
        return jdbc.query("""
                SELECT sequence,event_type,status_before,status_after,summary,
                       evidence_sha256,actor_user_id,occurred_at
                  FROM apr_incident_timeline
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND incident_id=:id
                 ORDER BY sequence
                """, base(context).addValue("id", incidentId),
                (row, number) -> new TimelineEntry(row.getLong(1), row.getString(2),
                        row.getString(3), row.getString(4), row.getString(5), row.getString(6),
                        row.getLong(7), instant(row.getTimestamp(8))));
    }

    private List<DiagnosticView> diagnostics(Context context, UUID incidentId) {
        return jdbc.query("""
                SELECT diagnostic_id,diagnostic_kind,redacted_payload::text,payload_sha256,
                       source_revision,observed_at
                  FROM apr_incident_diagnostics
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND incident_id=:id
                 ORDER BY observed_at,diagnostic_id
                """, base(context).addValue("id", incidentId), (row, number) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = canonical.read(row.getString(3), Map.class);
                    return new DiagnosticView(row.getObject(1, UUID.class), row.getString(2),
                            Map.copyOf(payload), row.getString(4), row.getString(5),
                            instant(row.getTimestamp(6)));
                });
    }

    private List<PlanView> plans(Context context, UUID incidentId) {
        return jdbc.query("""
                SELECT plan_id FROM apr_incident_recovery_plans
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND incident_id=:id
                 ORDER BY created_at,plan_id
                """, base(context).addValue("id", incidentId),
                (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> commands.requirePlan(context, incidentId, id, false)).toList();
    }

    private PostmortemView postmortem(Context context, UUID incidentId) {
        List<PostmortemView> rows = jdbc.query("""
                SELECT postmortem_id,summary,contributing_factors::text,
                       corrective_actions::text,evidence_sha256,version,recorded_at
                  FROM apr_incident_postmortems
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND incident_id=:id
                """, base(context).addValue("id", incidentId), (row, number) -> {
                    @SuppressWarnings("unchecked")
                    List<String> factors = canonical.read(row.getString(3), List.class);
                    @SuppressWarnings("unchecked")
                    List<String> actions = canonical.read(row.getString(4), List.class);
                    return new PostmortemView(row.getObject(1, UUID.class), row.getString(2),
                            List.copyOf(factors), List.copyOf(actions), row.getString(5),
                            row.getLong(6), instant(row.getTimestamp(7)));
                });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) throw IncidentRejected.forbidden(
                "Approval tenant is not active.");
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
