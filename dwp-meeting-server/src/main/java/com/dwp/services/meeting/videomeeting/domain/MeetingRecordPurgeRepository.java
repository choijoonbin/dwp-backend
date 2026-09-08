package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** Exact tenant/meeting deletes only. External product data and independent security receipts are never deleted. */
@Repository
public class MeetingRecordPurgeRepository {
    private final JdbcTemplate jdbc;
    public MeetingRecordPurgeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void purge(MeetingRecordDispositionRepository.Control control, UUID deletion, UUID audit,
            UUID fence, String worker) {
        long tenant = control.tenant();
        UUID meeting = control.meeting();
        // The manifest is constructed exclusively from an explicit metadata whitelist, not row_to_json.
        // No title, person, transcript, hash of source content, storage locator, or provider request is copied.
        String manifest = jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'recordingDeletions', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'artifactId',artifact_id,'commandId',deletion_command_id,
                        'providerCode',provider_code,'providerDeletionId',provider_deletion_id,'deletedAt',completed_at))
                        FROM vm_meeting_recording_deletion_commands WHERE tenant_id=? AND meeting_id=?), '[]'::jsonb),
                    'transcriptDeletions', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'artifactId',artifact_id,'commandId',deletion_command_id,
                        'providerCode',provider_code,'providerDeletionId',provider_deletion_id,'deletedAt',completed_at))
                        FROM vm_meeting_transcript_deletion_commands WHERE tenant_id=? AND meeting_id=?), '[]'::jsonb),
                    'reportDeletions', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'reportId',report_id,'deletionId',deletion_id,'deletedAt',deleted_at))
                        FROM vm_meeting_intelligence_deletions WHERE tenant_id=? AND meeting_id=?), '[]'::jsonb),
                    'recordEvents', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'eventId',event_id,'eventType',event_type,'occurredAt',occurred_at))
                        FROM vm_meeting_events WHERE tenant_id=? AND meeting_id=?), '[]'::jsonb))::text
                """, String.class, tenant, meeting, tenant, meeting, tenant, meeting, tenant, meeting);
        jdbc.update("""
                INSERT INTO vm_meeting_record_deletion_evidence (deletion_id,tenant_id,meeting_id,
                    control_version,meeting_version,policy_version,retention_until,authorization_audit_id,
                    purge_audit_id,fence_token,worker_id,receipt_manifest)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """, deletion, tenant, meeting, control.version(), control.meetingVersion(), control.policyVersion(),
                control.deadline(), control.auditId(), audit, fence, worker, manifest);
        // Non-cascading owner references must be removed before the exact parent DELETE.
        jdbc.update("DELETE FROM vm_meeting_intelligence_deletions WHERE tenant_id=? AND meeting_id=?", tenant, meeting);
        jdbc.update("DELETE FROM vm_personal_meeting_room_sessions WHERE tenant_id=? AND meeting_id=?", tenant, meeting);
        jdbc.update("DELETE FROM vm_meeting_facilitation_poll_votes WHERE tenant_id=? AND meeting_id=?", tenant, meeting);
        // Poll/question authors are NO ACTION participant FKs at a deeper cascade level.
        // Remove their already-expired owner aggregate while participant rows still exist.
        jdbc.update("DELETE FROM vm_meeting_facilitation_states WHERE tenant_id=? AND meeting_id=?", tenant, meeting);
        // These rows contain room locators/access windows and have no parent FK. Replay receipts are retained.
        jdbc.update("DELETE FROM vm_meeting_provider_events WHERE tenant_id=? AND meeting_id=?", tenant, meeting);
        jdbc.update("DELETE FROM vm_meeting_transcript_access_windows WHERE tenant_id=? AND meeting_id=?", tenant, meeting);
        int deleted = jdbc.update("""
                DELETE FROM vm_meetings WHERE tenant_id=? AND meeting_id=? AND version=?
                    AND lifecycle_state IN ('ENDED','CANCELLED')
                """, tenant, meeting, control.meetingVersion());
        if (deleted != 1) throw new IllegalStateException("Record disposition changed.");
        int marked = jdbc.update("""
                UPDATE vm_meeting_record_dispositions SET purged_at=clock_timestamp()
                 WHERE tenant_id=? AND meeting_id=? AND control_version=?
                   AND purge_authorized AND NOT legal_hold AND purged_at IS NULL
                """, tenant, meeting, control.version());
        if (marked != 1) throw new IllegalStateException("Record control changed.");
    }
}
