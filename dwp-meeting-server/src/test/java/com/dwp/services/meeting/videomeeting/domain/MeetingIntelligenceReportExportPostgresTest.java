package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingIntelligenceReportExportDtos;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingIntelligenceReportExportPostgresTest extends MeetingWorkspacePostgresFixture {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-08T04:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MeetingIntelligenceReportExportService exports;
    private MeetingIntelligencePayloadProtector protector;
    private UUID meetingId;
    private UUID reportId;
    private long actor;
    private byte[] plaintext;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void publishedReport() throws Exception {
        meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'ENDED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        actor = jdbc.queryForObject("""
                SELECT organizer_user_id FROM vm_meetings
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        plaintext = """
                {
                  "executiveSummary":{"text":"Published recap","citations":[]},
                  "topics":[],"decisions":[],"actionItems":[],
                  "openQuestions":[],"risks":[],
                  "conversationClimate":{"label":"ALIGNED","signals":[],"citations":[]}
                }
                """.getBytes(StandardCharsets.UTF_8);
        reportId = insertPublishedReport(sha256(plaintext));
        protector = mock(MeetingIntelligencePayloadProtector.class);
        when(protector.available()).thenReturn(true);
        when(protector.ready()).thenReturn(true);
        when(protector.unprotect(anyLong(), any(), any()))
                .thenAnswer(invocation -> plaintext.clone());
        exports = new MeetingIntelligenceReportExportService(
                meetings, new VideoMeetingIntelligenceRepository(jdbc), protector,
                new MeetingContentAccessPolicy(), audit, mapper,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void exportAndMetadataOnlyAuditCommitTogetherInTheDatabase() {
        var exported = as(1, actor, Set.of("APP.MEETINGS:VIEW"), () -> exports.export(
                meetingId, reportId, command(2), "recap-postgres-success"));

        assertThat(exported.reportVersion()).isEqualTo(2);
        assertThat(exported.payloadSha256()).isEqualTo(sha256(exported.content()));
        assertThat(jdbc.queryForMap("""
                SELECT payload ->> 'category' category,
                       payload ->> 'action' action,
                       payload ->> 'correlationId' correlation_id,
                       payload ->> 'targetId' target_id,
                       payload -> 'afterState' ->> 'meetingId' meeting_id,
                       payload -> 'afterState' ->> 'reportVersion' report_version,
                       payload -> 'afterState' ->> 'payloadSha256' payload_sha256
                  FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.intelligence.report-exported'
                """))
                .containsEntry("category", "DATA_EXPORT")
                .containsEntry("action", "meeting.intelligence.report-exported")
                .containsEntry("correlation_id", "recap-postgres-success")
                .containsEntry("target_id", reportId.toString())
                .containsEntry("meeting_id", meetingId.toString())
                .containsEntry("report_version", "2")
                .containsEntry("payload_sha256", exported.payloadSha256());
        String auditPayload = jdbc.queryForObject("""
                SELECT payload::text FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.intelligence.report-exported'
                """, String.class);
        assertThat(auditPayload).doesNotContain("Published recap", "executiveSummary");
    }

    @Test
    void currentParticipantAndExactVersionAreFencedBeforePayloadOrAudit() {
        jdbc.update("""
                UPDATE vm_meeting_participants SET attendance_state = 'DENIED'
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id = ?
                """, meetingId, actor);
        assertThatThrownBy(() -> as(1, actor, Set.of("APP.MEETINGS:VIEW"), () ->
                exports.export(meetingId, reportId, command(2), "recap-denied")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        jdbc.update("""
                UPDATE vm_meeting_participants SET attendance_state = 'LEFT'
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id = ?
                """, meetingId, actor);
        assertThatThrownBy(() -> as(1, actor, Set.of("APP.MEETINGS:VIEW"), () ->
                exports.export(meetingId, reportId, command(1), "recap-stale")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.intelligence.report-exported'
                """, Integer.class)).isZero();
    }

    @Test
    void databaseAuditFailurePreventsAFileResponseAndLeavesNoAuditRow() {
        jdbc.execute("""
                CREATE FUNCTION fail_recap_export_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.intelligence.report-exported' THEN
                        RAISE EXCEPTION 'simulated recap export audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_recap_export_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_recap_export_audit()
                """);

        assertThatThrownBy(() -> as(1, actor, Set.of("APP.MEETINGS:VIEW"), () ->
                exports.export(meetingId, reportId, command(2), "recap-audit-failure")))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.intelligence.report-exported'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM vm_meeting_intelligence_reports
                 WHERE tenant_id = 1 AND meeting_id = ? AND report_id = ?
                """, Long.class, meetingId, reportId)).isEqualTo(2);
    }

    private MeetingIntelligenceReportExportDtos.ExportCommand command(long version) {
        return new MeetingIntelligenceReportExportDtos.ExportCommand(version, "JSON");
    }

    private UUID insertPublishedReport(String payloadSha256) {
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                 ORDER BY artifact_id LIMIT 1
                """, UUID.class, meetingId);
        UUID noticeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, 991, FALSE, TRUE, TRUE, ?)
                """, noticeId, meetingId, actor);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_runs (
                    run_id, tenant_id, meeting_id, source_artifact_id, source_sha256,
                    content_notice_id, consent_snapshot_sha256, analysis_profile,
                    output_language, processing_region, execution_fence,
                    lease_expires_at, attempt_count, run_state, provider_code,
                    provider_model, prompt_version, schema_version, idempotency_key,
                    request_sha256, requested_at, requested_by, started_at, completed_at)
                VALUES (?, 1, ?, ?, ?, ?, ?, 'STANDARD_RECAP_V1', 'ko-KR',
                        'ap-northeast-2', ?, ?, 1, 'SUCCEEDED', 'agent', 'model-v1',
                        'governed-recap-v1', 'meeting-intelligence-v1', ?, ?, ?, ?, ?, ?)
                """, runId, meetingId, artifactId, "a".repeat(64), noticeId,
                "b".repeat(64), UUID.randomUUID(), NOW.minusMinutes(10),
                "recap-export:" + runId, "c".repeat(64), NOW.minusMinutes(20), actor,
                NOW.minusMinutes(19), NOW.minusMinutes(18));
        UUID newReportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_reports (
                    report_id, tenant_id, meeting_id, run_id, report_state, audience,
                    encrypted_payload, payload_sha256, source_sha256, schema_version,
                    retention_until, legal_hold, approved_at, approved_by,
                    published_at, published_by, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, 1, ?, ?, 'PUBLISHED', 'MEETING_PARTICIPANTS',
                        'ciphertext', ?, ?, 'meeting-intelligence-v1',
                        ?, FALSE, ?, ?, ?, ?, 2, ?, ?, ?, ?)
                """, newReportId, meetingId, runId, payloadSha256, "a".repeat(64),
                NOW.plusDays(30), NOW.minusMinutes(17), actor,
                NOW.minusMinutes(16), actor, NOW.minusMinutes(18), actor,
                NOW.minusMinutes(16), actor);
        return newReportId;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
