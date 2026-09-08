package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** No external IO and no caller-provided predicate. Unknown/unfinished content fails closed. */
@Repository
public class MeetingRecordRetentionGuard {
    private final JdbcTemplate jdbc;
    private final MeetingRecordDispositionRepository records;
    public MeetingRecordRetentionGuard(JdbcTemplate jdbc, MeetingRecordDispositionRepository records) {
        this.jdbc = jdbc; this.records = records;
    }

    public List<String> reasons(long tenant, UUID meeting, MeetingRecordDispositionRepository.RecordSnapshot snapshot,
            MeetingRecordDispositionRepository.Control control, boolean lock) {
        List<String> reasons = new ArrayList<>();
        if (snapshot == null) return List.of("RECORD_NOT_FOUND");
        if (!List.of("ENDED", "CANCELLED").contains(snapshot.lifecycle())) reasons.add("RECORD_NOT_TERMINAL");
        if (!List.of("INACTIVE", "ENDED").contains(snapshot.mediaState())) reasons.add("MEDIA_NOT_CLOSED");
        if (snapshot.provider() != null && snapshot.providerClosedAt() == null) reasons.add("PROVIDER_ROOM_DELETION_UNPROVEN");
        if (snapshot.deadline().isAfter(records.now())) reasons.add("RECORD_RETENTION_NOT_EXPIRED");
        if (control != null) {
            if (control.hold()) reasons.add("RECORD_LEGAL_HOLD");
            if (control.meetingVersion() != snapshot.meetingVersion()
                    || control.policyVersion() != snapshot.policyVersion()
                    || !control.deadline().isEqual(snapshot.deadline())) reasons.add("AUTHORIZATION_SNAPSHOT_CHANGED");
        }
        if (lock) {
            jdbc.query("SELECT report_id FROM vm_meeting_intelligence_reports WHERE tenant_id=? AND meeting_id=? FOR UPDATE",
                    (rs, n) -> rs.getObject(1), tenant, meeting);
            jdbc.query("SELECT artifact_id FROM vm_meeting_artifacts WHERE tenant_id=? AND meeting_id=? FOR UPDATE",
                    (rs, n) -> rs.getObject(1), tenant, meeting);
        }
        if (exists("vm_meeting_intelligence_reports", "legal_hold", tenant, meeting)) reasons.add("REPORT_LEGAL_HOLD");
        if (exists("vm_meeting_intelligence_reports", "retention_until > clock_timestamp() OR report_state <> 'DELETED'"
                + " OR encrypted_payload IS NOT NULL OR payload_sha256 IS NOT NULL OR NOT EXISTS"
                + " (SELECT 1 FROM vm_meeting_intelligence_deletions d WHERE d.tenant_id=t.tenant_id"
                + " AND d.meeting_id=t.meeting_id AND d.report_id=t.report_id)", tenant, meeting)) {
            reasons.add("REPORT_DELETION_UNPROVEN");
        }
        if (artifactUnsafe(tenant, meeting)) reasons.add("EXTERNAL_ARTIFACT_DELETION_UNPROVEN");
        if (exists("vm_meeting_chat_messages", "retention_until > clock_timestamp() OR message_text IS NOT NULL"
                + " OR NOT EXISTS (SELECT 1 FROM vm_meeting_chat_retention_evidence e"
                + " WHERE e.tenant_id=t.tenant_id AND e.meeting_id=t.meeting_id AND e.message_id=t.message_id)", tenant, meeting)) {
            reasons.add("CHAT_DELETION_UNPROVEN");
        }
        if (exists("vm_meeting_preparation_materials", "retention_until > clock_timestamp()", tenant, meeting)
                || exists("vm_meeting_facilitation_states", "retention_until > clock_timestamp()", tenant, meeting)
                || exists("vm_meeting_facilitation_questions", "retention_until > clock_timestamp()", tenant, meeting)
                || exists("vm_meeting_facilitation_polls", "retention_until > clock_timestamp()", tenant, meeting)) {
            reasons.add("CHILD_RETENTION_NOT_EXPIRED");
        }
        if (active(tenant, meeting)) reasons.add("CONTENT_PROCESSING_ACTIVE_OR_UNRESOLVED");
        if (control == null || !records.published(tenant, meeting, control.auditId())) reasons.add("AUTHORIZATION_AUDIT_NOT_PUBLISHED");
        if (unpublishedAudit(tenant, meeting)) reasons.add("RELATED_AUDIT_NOT_PUBLISHED");
        return List.copyOf(reasons);
    }

    private boolean artifactUnsafe(long tenant, UUID meeting) {
        return exists("vm_meeting_artifacts", """
                storage_provider IS NOT NULL OR object_key IS NOT NULL
                OR (artifact_state = 'DELETED' AND retention_until IS NULL)
                OR (retention_until IS NOT NULL AND retention_until > clock_timestamp())
                OR NOT (artifact_state = 'NONE' AND size_bytes IS NULL AND sha256 IS NULL
                    AND registered_at IS NULL AND finalized_at IS NULL AND recording_session_id IS NULL
                    AND metadata = '{}'::jsonb AND NOT EXISTS (SELECT 1 FROM vm_meeting_intelligence_runs r
                        WHERE r.tenant_id=t.tenant_id AND r.meeting_id=t.meeting_id AND r.source_artifact_id=t.artifact_id)
                    OR artifact_state = 'DELETED' AND (
                        artifact_type = 'RECORDING' AND recording_deleted_at IS NOT NULL AND EXISTS (
                            SELECT 1 FROM vm_meeting_recording_deletion_commands d
                             WHERE d.tenant_id=t.tenant_id AND d.meeting_id=t.meeting_id
                               AND d.artifact_id=t.artifact_id AND d.deletion_command_id=t.recording_deletion_command_id
                               AND d.provider_code=t.recording_deletion_provider_code
                               AND d.command_state='SUCCEEDED' AND d.completed_at IS NOT NULL
                               AND d.provider_deletion_id IS NOT NULL)
                        OR artifact_type = 'TRANSCRIPT' AND transcript_deleted_at IS NOT NULL AND EXISTS (
                            SELECT 1 FROM vm_meeting_transcript_deletion_commands d
                             WHERE d.tenant_id=t.tenant_id AND d.meeting_id=t.meeting_id
                               AND d.artifact_id=t.artifact_id AND d.deletion_command_id=t.transcript_deletion_command_id
                               AND d.provider_code=t.transcript_deletion_provider_code
                               AND d.command_state='SUCCEEDED' AND d.completed_at IS NOT NULL
                               AND d.provider_deletion_id IS NOT NULL)))
                """, tenant, meeting);
    }

    private boolean active(long tenant, UUID meeting) {
        return exists("vm_meeting_recording_sessions", "recording_state <> 'STOPPED'", tenant, meeting)
                || exists("vm_meeting_recording_provider_commands", "command_state <> 'SUCCEEDED'", tenant, meeting)
                || exists("vm_meeting_recording_deletion_commands", "command_state <> 'SUCCEEDED'", tenant, meeting)
                || exists("vm_meeting_transcript_deletion_commands", "command_state <> 'SUCCEEDED'", tenant, meeting)
                || exists("vm_meeting_provider_connections", "connection_state = 'JOINED'", tenant, meeting)
                || exists("vm_meeting_media_operations", "operation_state <> 'SUCCEEDED'", tenant, meeting)
                || exists("vm_meeting_media_upgrades", "upgrade_state <> 'SUCCEEDED'", tenant, meeting)
                || exists("vm_meeting_intelligence_runs", "run_state = 'RUNNING'", tenant, meeting)
                || exists("vm_meeting_intelligence_auto_requests", "request_state IN ('PENDING','RUNNING')", tenant, meeting)
                || exists("vm_meeting_provider_events", "processing_state IN ('CLEANUP_REQUIRED','CLEANUP_RUNNING','CLEANUP_FAILED')", tenant, meeting)
                || exists("vm_meeting_invitation_outbox", "delivery_state IN ('PENDING','FAILED')", tenant, meeting);
    }

    private boolean unpublishedAudit(long tenant, UUID meeting) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sys_audit_outbox o WHERE o.tenant_id=?
                    AND (o.status <> 'PUBLISHED' OR o.published_at IS NULL)
                    AND (o.payload->>'targetId' = ? OR o.payload->'afterState'->>'meetingId' = ?
                        OR o.payload->>'targetId' IN (
                            SELECT artifact_id::text FROM vm_meeting_artifacts WHERE tenant_id=? AND meeting_id=?
                            UNION ALL SELECT report_id::text FROM vm_meeting_intelligence_reports WHERE tenant_id=? AND meeting_id=?
                            UNION ALL SELECT message_id::text FROM vm_meeting_chat_messages WHERE tenant_id=? AND meeting_id=?)))
                """, Boolean.class, tenant, meeting.toString(), meeting.toString(), tenant, meeting, tenant, meeting, tenant, meeting));
    }

    // All callers below are static, private, product-owned predicates; never accept external table/SQL input.
    private boolean exists(String table, String predicate, long tenant, UUID meeting) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table
                + " t WHERE tenant_id=? AND meeting_id=? AND (" + predicate + "))", Boolean.class, tenant, meeting));
    }
}
