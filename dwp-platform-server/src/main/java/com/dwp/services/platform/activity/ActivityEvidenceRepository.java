package com.dwp.services.platform.activity;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ActivityEvidenceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ActivityEvidenceRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record AuditObservation(UUID eventId, UUID auditId, String recordHash,
                                   String checkpointStatus, OffsetDateTime verifiedAt) { }

    public Optional<AuditObservation> evidence(
            Long tenant, Long user, Set<String> permissions, UUID id) {
        return evidence(tenant, user, permissions, id, false);
    }

    public Optional<AuditObservation> evidence(
            Long tenant, Long user, Set<String> permissions, UUID id, boolean localFixtures) {
        // Re-evaluate source ACL in the same statement that reads the receipt. The legacy
        // source FK proves linkage; only the audit owner's persisted checkpoint reports integrity.
        return jdbc.query("""
                SELECT e.activity_event_id, e.audit_record_id, a.record_hash,
                       checkpoint.verification_status, checkpoint.verified_at
                  FROM wrk_activity_events e
                  LEFT JOIN sys_platform_audit_events p
                    ON p.tenant_id = e.tenant_id AND p.audit_event_id = e.audit_record_id
                  LEFT JOIN sys_audit_events a
                    ON a.tenant_id = p.tenant_id AND a.event_id = p.audit_event_id
                   AND a.occurred_at = p.occurred_at
                  LEFT JOIN sys_audit_integrity_checkpoints checkpoint
                    ON checkpoint.tenant_id = a.tenant_id
                   AND checkpoint.checkpoint_date = (a.occurred_at AT TIME ZONE 'UTC')::date
                   AND a.ingested_at <= checkpoint.created_at
                   AND a.occurred_at BETWEEN checkpoint.first_event_at AND checkpoint.last_event_at
                 WHERE
                """ + ActivityRepository.AUTHORIZED + " AND e.activity_event_id = :id",
                access(tenant, user, permissions, localFixtures).addValue("id", id),
                (rs, n) -> new AuditObservation(rs.getObject("activity_event_id", UUID.class),
                        rs.getObject("audit_record_id", UUID.class), rs.getString("record_hash"),
                        rs.getString("verification_status"), rs.getObject("verified_at", OffsetDateTime.class)))
                .stream().findFirst();
    }

    public Optional<AuditObservation> agentEvidence(Long tenant, Long user, UUID auditId) {
        return jdbc.query("""
                SELECT a.target_id::uuid AS run_id, a.event_id, a.record_hash,
                       checkpoint.verification_status, checkpoint.verified_at
                  FROM sys_audit_events a
                  LEFT JOIN sys_audit_integrity_checkpoints checkpoint
                    ON checkpoint.tenant_id = a.tenant_id
                   AND checkpoint.checkpoint_date = (a.occurred_at AT TIME ZONE 'UTC')::date
                   AND a.ingested_at <= checkpoint.created_at
                   AND a.occurred_at BETWEEN checkpoint.first_event_at AND checkpoint.last_event_at
                 WHERE a.tenant_id = :tenant AND a.actor_type = 'USER'
                   AND a.actor_id = :user AND a.event_id = :id
                   AND a.source_service = 'dwp-agent-runtime' AND a.target_type = 'AGENT_RUN'
                   AND a.target_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                 ORDER BY a.occurred_at DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("tenant", tenant)
                        .addValue("user", user.toString()).addValue("id", auditId),
                (rs, n) -> new AuditObservation(rs.getObject("run_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("record_hash"),
                        rs.getString("verification_status"), rs.getObject("verified_at", OffsetDateTime.class)))
                .stream().findFirst();
    }

    public List<ActivityEvidenceDtos.SourceStatus> sources(
            Long tenant, Long user, Set<String> permissions, OffsetDateTime now,
            boolean localFixtures) {
        // Never read connector credentials, raw errors, another subject's status, or
        // tenant-wide success rates. An empty result does not mean every connector is healthy.
        return jdbc.query("""
                SELECT connector.connector_key, connector.display_name, stream.resource_kind,
                       CASE WHEN connector.lifecycle_state <> 'ACTIVE' THEN connector.lifecycle_state
                            WHEN connector.policy_state <> 'APPROVED' THEN connector.policy_state
                            WHEN subject.consent_state <> 'CONNECTED' THEN subject.consent_state
                            WHEN connector.health_state <> 'HEALTHY' THEN connector.health_state
                            ELSE stream.stream_state END AS observed_status,
                       stream.last_attempt_at, stream.last_success_at
                  FROM int_productivity_sync_streams stream
                  JOIN int_productivity_subjects subject
                    ON subject.tenant_id = stream.tenant_id
                   AND subject.productivity_subject_id = stream.productivity_subject_id
                  JOIN int_productivity_connectors connector
                    ON connector.tenant_id = subject.tenant_id
                   AND connector.productivity_connector_id = subject.productivity_connector_id
                 WHERE subject.tenant_id = :tenant AND subject.user_id = :user
                   AND connector.lifecycle_state <> 'RETIRED'
                   AND (:localFixtures OR connector.connector_key NOT LIKE 'activity-local-joonbin-%')
                   AND ((stream.resource_kind = 'MAIL' AND :mailView)
                     OR (stream.resource_kind = 'CALENDAR' AND :calendarView))
                 ORDER BY connector.connector_key, stream.resource_kind
                """, access(tenant, user, permissions, localFixtures)
                        .addValue("mailView", permissions.contains("APP.MAIL:VIEW"))
                        .addValue("calendarView", permissions.contains("APP.CALENDAR:VIEW")),
                (rs, n) -> new ActivityEvidenceDtos.SourceStatus(
                        rs.getString("connector_key") + ":" + rs.getString("resource_kind"),
                        rs.getString("display_name"), rs.getString("resource_kind"),
                        rs.getString("observed_status"), rs.getObject("last_attempt_at", OffsetDateTime.class),
                        rs.getObject("last_success_at", OffsetDateTime.class), now,
                        rs.getString("connector_key").startsWith("activity-local-joonbin-")
                                ? "LOCAL_FIXTURE" : "PERSONAL_SYNC_LEDGER"));
    }

    public List<ActivityEvidenceDtos.SourceStatus> sources(
            Long tenant, Long user, Set<String> permissions, OffsetDateTime now) {
        return sources(tenant, user, permissions, now, false);
    }

    private MapSqlParameterSource access(
            Long tenant, Long user, Set<String> permissions, boolean localFixtures) {
        return new MapSqlParameterSource().addValue("tenant", tenant).addValue("user", user)
                .addValue("localFixtures", localFixtures)
                .addValue("workView", permissions.contains("APP.WORK:VIEW"))
                .addValue("appsView", permissions.contains("APP.APPS:VIEW"))
                .addValue("permissions", permissions.isEmpty() ? Set.of("__NONE__") : permissions);
    }
}
