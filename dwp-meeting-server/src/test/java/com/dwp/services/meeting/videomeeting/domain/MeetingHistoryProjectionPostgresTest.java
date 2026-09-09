package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos.HistoryPublicationFilter;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos.HistoryRetentionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingHistoryProjectionPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final long TENANT = 77;
    private static final long HOST = 7_701;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-08T00:00:00Z");

    private UUID meeting;
    private UUID draft;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void historyEvidence() {
        meeting = meeting(TENANT, HOST, "Policy parity");
        participant(meeting, HOST, "ORGANIZER", "LEFT");
        participant(meeting, 7_702, "ATTENDEE", "LEFT");
        participant(meeting, 7_703, "ATTENDEE", "INVITED");
        participant(meeting, 7_704, "ATTENDEE", "INVITED");
        participant(meeting, 7_705, "ATTENDEE", "INVITED");
        participant(meeting, 7_706, "ATTENDEE", "INVITED");
        participant(meeting, 7_707, "ATTENDEE", "INVITED");
        jdbc.update("""
                UPDATE vm_meeting_participants SET attendance_state = 'DENIED'
                 WHERE tenant_id = ? AND meeting_id = ? AND user_id = ?
                """, TENANT, meeting, 7_707);
        report(meeting, "PUBLISHED", NOW.minusHours(4), NOW.plusDays(90));
        draft = report(meeting, "DRAFT", NOW.minusHours(2), NOW.plusDays(60));
        report(meeting, "REJECTED", NOW.minusHours(1), NOW.minusMinutes(1));
        report(meeting, "DELETED", NOW.minusMinutes(30), NOW.plusDays(60));
        grant(draft, 7_704, null, null);
        grant(draft, 7_705, NOW.minusMinutes(20), null);
        grant(draft, 7_706, NOW.plusDays(1), NOW.minusMinutes(10));
        control(meeting, NOW.plusDays(90), false);
    }

    @Test
    void reportProjectionMatchesCurrentContentAccessPolicyAndLatestVisibleOrdering() {
        assertThat(item(TENANT, HOST).publicationState()).isEqualTo("DRAFT");
        assertThat(item(TENANT, 7_702).publicationState()).isEqualTo("PUBLISHED");
        assertThat(item(TENANT, 7_703).publicationState()).isEqualTo("NONE");
        assertThat(item(TENANT, 7_704).publicationState()).isEqualTo("DRAFT");
        assertThat(item(TENANT, 7_705).publicationState()).isEqualTo("NONE");
        assertThat(item(TENANT, 7_706).publicationState()).isEqualTo("NONE");
        assertThat(history(TENANT, 7_707, HistoryPublicationFilter.ALL,
                HistoryRetentionFilter.ALL, 0, 100).total()).isZero();
    }

    @Test
    void publicationAndRetentionFiltersRunBeforePaginationAndNeverDuplicateOrLeakCounts() {
        UUID expiring = meeting(TENANT, HOST, "Expiring");
        participant(expiring, HOST, "ORGANIZER", "LEFT");
        control(expiring, NOW.plusDays(5), false);
        UUID held = meeting(TENANT, HOST, "Held");
        participant(held, HOST, "ORGANIZER", "LEFT");
        control(held, NOW.minusDays(1), true);
        UUID expired = meeting(TENANT, HOST, "Expired");
        participant(expired, HOST, "ORGANIZER", "LEFT");
        control(expired, NOW.minusDays(1), false);
        UUID unconfigured = meeting(TENANT, HOST, "Unconfigured");
        participant(unconfigured, HOST, "ORGANIZER", "LEFT");
        UUID foreign = meeting(78, HOST, "Cross tenant private title");
        participant(78, foreign, HOST, "ORGANIZER", "LEFT");
        control(78, foreign, NOW.plusDays(90), false);

        var all = history(TENANT, HOST, HistoryPublicationFilter.ALL,
                HistoryRetentionFilter.ALL, 0, 100);
        assertThat(all.items()).extracting(item -> item.card().meeting().meetingId())
                .containsExactlyInAnyOrder(meeting, expiring, held, expired, unconfigured)
                .doesNotContain(foreign);
        assertThat(all.items()).extracting(item -> item.card().meeting().meetingId())
                .doesNotHaveDuplicates();
        assertThat(all.total()).isEqualTo(5);

        var expiringPage = history(TENANT, HOST, HistoryPublicationFilter.NONE,
                HistoryRetentionFilter.EXPIRING_SOON, 0, 1);
        assertThat(expiringPage.total()).isOne();
        assertThat(expiringPage.items()).singleElement().satisfies(item ->
                assertThat(item.card().meeting().meetingId()).isEqualTo(expiring));
        assertThat(history(TENANT, HOST, HistoryPublicationFilter.NONE,
                HistoryRetentionFilter.EXPIRING_SOON, 1, 1).items()).isEmpty();
        assertThat(history(TENANT, HOST, HistoryPublicationFilter.ALL,
                HistoryRetentionFilter.LEGAL_HOLD, 0, 100).items())
                .singleElement().satisfies(item ->
                        assertThat(item.card().meeting().meetingId()).isEqualTo(held));
        assertThat(item(TENANT, HOST).retentionUntil()).isEqualTo(NOW.plusDays(90));
    }

    private MeetingHistoryProjectionRepository.HistoryItem item(long tenant, long user) {
        return history(tenant, user, HistoryPublicationFilter.ALL,
                HistoryRetentionFilter.ALL, 0, 100).items().stream()
                .filter(item -> item.card().meeting().meetingId().equals(meeting))
                .findFirst().orElseThrow();
    }

    private MeetingHistoryProjectionRepository.PagedHistory history(
            long tenant,
            long user,
            HistoryPublicationFilter publication,
            HistoryRetentionFilter retention,
            int page,
            int size) {
        return meetings.historyProjection(
                tenant, user, page, size, false, publication, retention, NOW);
    }

    private UUID meeting(long tenant, long organizer, String title) {
        UUID id = UUID.randomUUID();
        String code = id.toString().replace("-", "").substring(0, 12).toUpperCase()
                .replace('0', 'G').replace('1', 'H');
        jdbc.update("""
                INSERT INTO vm_meetings (
                    meeting_id, tenant_id, title, lifecycle_state, join_code,
                    provider, room_name, media_incarnation, media_access_state,
                    organizer_user_id, organizer_name, started_at, ended_at, ended_by,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'ENDED', ?, 'LIVEKIT', ?, ?, 'ENDED',
                        ?, 'History host', ?, ?, ?, ?, ?)
                """, id, tenant, title, code, "history-" + id, UUID.randomUUID(), organizer,
                NOW.minusDays(2).minusHours(1), NOW.minusDays(2), organizer, organizer, organizer);
        return id;
    }

    private void participant(UUID meetingId, long user, String role, String state) {
        participant(TENANT, meetingId, user, role, state);
    }

    private void participant(long tenant, UUID meetingId, long user, String role, String state) {
        boolean admitted = List.of("ADMITTED", "JOINED", "LEFT").contains(state);
        boolean joined = List.of("JOINED", "LEFT").contains(state);
        jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    tenant_id, meeting_id, user_id, email_address, display_name,
                    participant_role, attendance_state, admitted_at, joined_at, left_at,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, 'History viewer', ?, ?, ?, ?, ?, ?, ?)
                """, tenant, meetingId, user, user + "-" + meetingId + "@example.test", role, state,
                admitted ? NOW.minusDays(2) : null,
                joined ? NOW.minusDays(2) : null,
                "LEFT".equals(state) ? NOW.minusDays(2).plusHours(1) : null, user, user);
    }

    private UUID report(
            UUID meetingId, String state, OffsetDateTime createdAt, OffsetDateTime retentionUntil) {
        UUID notice = UUID.randomUUID();
        Integer noticeRevision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(notice_revision), 0) + 1
                  FROM vm_meeting_content_notices
                 WHERE tenant_id = ? AND meeting_id = ?
                """, Integer.class, TENANT, meetingId);
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, ?, ?, ?, FALSE, TRUE, TRUE, ?)
                """, notice, TENANT, meetingId, noticeRevision, HOST);
        UUID artifact = sourceArtifact(meetingId);
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_runs (
                    run_id, tenant_id, meeting_id, source_artifact_id, source_sha256,
                    content_notice_id, consent_snapshot_sha256, analysis_profile,
                    output_language, processing_region, execution_fence, lease_expires_at,
                    attempt_count, run_state, provider_code, provider_model, prompt_version,
                    schema_version, idempotency_key, request_sha256, requested_at,
                    requested_by, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'STANDARD_RECAP_V1', 'ko-KR',
                        'ap-northeast-2', ?, ?, 1, 'SUCCEEDED', 'agent', 'model-v1',
                        'governed-recap-v1', 'meeting-intelligence-v1', ?, ?, ?, ?, ?, ?)
                """, run, TENANT, meetingId, artifact, "a".repeat(64), notice,
                "b".repeat(64), UUID.randomUUID(), createdAt.plusMinutes(1),
                "history:" + run, "c".repeat(64), createdAt.minusMinutes(2), HOST,
                createdAt.minusMinutes(2), createdAt.minusMinutes(1));
        UUID report = UUID.randomUUID();
        boolean published = "PUBLISHED".equals(state);
        boolean approved = published || "APPROVED".equals(state);
        boolean deleted = "DELETED".equals(state);
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_reports (
                    report_id, tenant_id, meeting_id, run_id, report_state, audience,
                    encrypted_payload, payload_sha256, source_sha256, schema_version,
                    retention_until, legal_hold, approved_at, approved_by,
                    published_at, published_by, deleted_at, deleted_by,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'meeting-intelligence-v1',
                        ?, FALSE, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, report, TENANT, meetingId, run, state,
                published ? "MEETING_PARTICIPANTS" : "PRIVATE_REVIEWERS",
                deleted ? null : "ciphertext", deleted ? null : "d".repeat(64),
                "a".repeat(64), retentionUntil,
                approved ? createdAt.plusMinutes(1) : null, approved ? HOST : null,
                published ? createdAt.plusMinutes(2) : null, published ? HOST : null,
                deleted ? createdAt.plusMinutes(1) : null, deleted ? HOST : null,
                createdAt, HOST, createdAt, HOST);
        return report;
    }

    private UUID sourceArtifact(UUID meetingId) {
        List<UUID> existing = jdbc.query("""
                SELECT artifact_id
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                """, (resultSet, rowNumber) -> resultSet.getObject("artifact_id", UUID.class),
                TENANT, meetingId);
        if (!existing.isEmpty()) return existing.getFirst();
        UUID artifact = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_artifacts (
                    artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'TRANSCRIPT', 'UNAVAILABLE', ?, ?)
                """, artifact, TENANT, meetingId, HOST, HOST);
        return artifact;
    }

    private void grant(
            UUID report, long user, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        jdbc.update("""
                INSERT INTO vm_meeting_content_acl (
                    acl_id, tenant_id, meeting_id, content_type, content_id,
                    principal_user_id, permission, granted_at, granted_by,
                    expires_at, revoked_at, revoked_by, reason_code)
                VALUES (?, ?, ?, 'INTELLIGENCE_REPORT', ?, ?, 'VIEW', ?, ?, ?, ?, ?, 'HISTORY_REVIEW')
                """, UUID.randomUUID(), TENANT, meeting, report, user, NOW.minusDays(2), HOST,
                expiresAt, revokedAt, revokedAt == null ? null : HOST);
    }

    private void control(UUID meetingId, OffsetDateTime deadline, boolean hold) {
        control(TENANT, meetingId, deadline, hold);
    }

    private void control(long tenant, UUID meetingId, OffsetDateTime deadline, boolean hold) {
        jdbc.update("""
                INSERT INTO vm_meeting_record_dispositions (
                    tenant_id, meeting_id, meeting_version, policy_version,
                    retention_until, legal_hold, purge_authorized, control_version,
                    authorization_audit_id, updated_by)
                VALUES (?, ?, 0, 0, ?, ?, FALSE, 1, ?, ?)
                """, tenant, meetingId, deadline, hold, UUID.randomUUID(), HOST);
    }
}
