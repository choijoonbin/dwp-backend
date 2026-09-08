package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MeetingRecordDispositionRepository {
    private final JdbcTemplate jdbc;
    public MeetingRecordDispositionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockScope(long tenant, UUID meeting) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 38))", (rs, n) -> 0,
                tenant + ":" + meeting);
    }

    public RecordSnapshot snapshot(long tenant, UUID meeting, boolean lock) {
        return jdbc.query("""
                SELECT m.version AS meeting_version, p.version AS policy_version,
                       COALESCE(m.ended_at, m.updated_at) + p.retention_days * INTERVAL '1 day' AS deadline,
                       m.lifecycle_state, m.media_access_state,
                       m.provider, m.provider_room_closed_at
                  FROM vm_meetings m JOIN vm_tenant_policies p ON p.tenant_id = m.tenant_id
                 WHERE m.tenant_id = ? AND m.meeting_id = ?
                """ + (lock ? " FOR UPDATE OF m, p" : ""), (rs, n) -> new RecordSnapshot(
                rs.getLong("meeting_version"), rs.getLong("policy_version"),
                rs.getObject("deadline", OffsetDateTime.class), rs.getString("lifecycle_state"),
                rs.getString("media_access_state"), rs.getString("provider"),
                rs.getObject("provider_room_closed_at", OffsetDateTime.class)), tenant, meeting)
                .stream().findFirst().orElse(null);
    }

    public Control control(long tenant, UUID meeting, boolean lock) {
        return jdbc.query("SELECT * FROM vm_meeting_record_dispositions WHERE tenant_id = ? AND meeting_id = ?"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Control(tenant, meeting,
                rs.getLong("meeting_version"), rs.getLong("policy_version"),
                rs.getObject("retention_until", OffsetDateTime.class), rs.getBoolean("legal_hold"),
                rs.getBoolean("purge_authorized"), rs.getLong("control_version"),
                rs.getObject("authorization_audit_id", UUID.class),
                rs.getObject("purged_at", OffsetDateTime.class)), tenant, meeting).stream().findFirst().orElse(null);
    }

    public void save(long tenant, UUID meeting, RecordSnapshot snapshot, long expected, boolean hold,
            boolean authorized, UUID auditId, long actor) {
        int changed = jdbc.update("""
                INSERT INTO vm_meeting_record_dispositions (tenant_id, meeting_id, meeting_version,
                    policy_version, retention_until, legal_hold, purge_authorized, control_version,
                    authorization_audit_id, updated_by)
                SELECT ?, ?, ?, ?, ?, ?, ?, 1, ?, ? WHERE ? = 0
                ON CONFLICT (tenant_id, meeting_id) DO NOTHING
                """, tenant, meeting, snapshot.meetingVersion(), snapshot.policyVersion(), snapshot.deadline(),
                hold, authorized, auditId, actor, expected);
        if (expected > 0) changed = jdbc.update("""
                UPDATE vm_meeting_record_dispositions SET meeting_version = ?, policy_version = ?,
                    retention_until = ?, legal_hold = ?, purge_authorized = ?, control_version = control_version + 1,
                    authorization_audit_id = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND control_version = ? AND purged_at IS NULL
                """, snapshot.meetingVersion(), snapshot.policyVersion(), snapshot.deadline(), hold, authorized,
                auditId, actor, tenant, meeting, expected);
        if (changed != 1) MeetingWorkspacePolicy.version(-1, expected);
    }

    public boolean published(long tenant, UUID meeting, UUID event) {
        if (event == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sys_audit_outbox WHERE tenant_id = ? AND event_id = ?
                    AND status = 'PUBLISHED' AND published_at IS NOT NULL
                    AND payload->>'targetId' = ?
                    AND payload->>'action' = 'meeting.record.retention.controlled')
                """, Boolean.class, tenant, event, meeting.toString()));
    }

    public List<Scope> candidates(int batch) {
        return jdbc.query("""
                SELECT tenant_id, meeting_id FROM vm_meeting_record_dispositions
                 WHERE purge_authorized AND NOT legal_hold AND purged_at IS NULL
                   AND retention_until <= clock_timestamp()
                 ORDER BY last_evaluated_at NULLS FIRST, retention_until, tenant_id, meeting_id LIMIT ?
                """, (rs, n) -> new Scope(rs.getLong("tenant_id"), rs.getObject("meeting_id", UUID.class)), batch);
    }

    public void evaluated(long tenant, UUID meeting) {
        jdbc.update("UPDATE vm_meeting_record_dispositions SET last_evaluated_at=clock_timestamp() WHERE tenant_id=? AND meeting_id=?",
                tenant, meeting);
    }

    public OffsetDateTime now() { return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class); }
    public record Scope(long tenant, UUID meeting) { }
    public record RecordSnapshot(long meetingVersion, long policyVersion, OffsetDateTime deadline,
            String lifecycle, String mediaState, String provider, OffsetDateTime providerClosedAt) { }
    public record Control(long tenant, UUID meeting, long meetingVersion, long policyVersion,
            OffsetDateTime deadline, boolean hold, boolean authorized, long version, UUID auditId,
            OffsetDateTime purgedAt) { }
}
