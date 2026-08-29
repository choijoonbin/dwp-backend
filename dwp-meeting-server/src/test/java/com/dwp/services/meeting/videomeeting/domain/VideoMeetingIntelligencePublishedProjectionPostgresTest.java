package com.dwp.services.meeting.videomeeting.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class VideoMeetingIntelligencePublishedProjectionPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private VideoMeetingIntelligenceRepository repository;

    @BeforeEach
    void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new VideoMeetingIntelligenceRepository(jdbc);
    }

    @Test
    void newerDraftAndExpiredReportCannotDisplaceLatestPublishedRecap() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'ENDED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        long actor = jdbc.queryForObject("""
                SELECT organizer_user_id FROM vm_meetings
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                """, UUID.class, meetingId);
        UUID noticeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, 991, FALSE, TRUE, TRUE, ?)
                """, noticeId, meetingId, actor);

        UUID olderPublished = report(
                meetingId, artifactId, noticeId, actor, "PUBLISHED",
                now.minusMinutes(20), now.minusMinutes(10), now.plusDays(10));
        UUID expected = report(
                meetingId, artifactId, noticeId, actor, "PUBLISHED",
                now.minusMinutes(8), now.minusMinutes(5), now.plusDays(10));
        report(meetingId, artifactId, noticeId, actor, "DRAFT",
                now.minusMinutes(2), null, now.plusDays(10));
        report(meetingId, artifactId, noticeId, actor, "PUBLISHED",
                now.minusDays(3), now.minusMinutes(1), now.minusDays(1));

        var selected = repository.latestPublishedReport(1, meetingId, now).orElseThrow();

        assertThat(selected.reportId()).isEqualTo(expected);
        assertThat(selected.reportId()).isNotEqualTo(olderPublished);
        assertThat(selected.state()).isEqualTo(
                VideoMeetingIntelligenceModels.ReportState.PUBLISHED);
        assertThat(selected.audience()).isEqualTo(
                VideoMeetingIntelligenceModels.Audience.MEETING_PARTICIPANTS);
    }

    private UUID report(
            UUID meetingId,
            UUID artifactId,
            UUID noticeId,
            long actor,
            String state,
            OffsetDateTime createdAt,
            OffsetDateTime publishedAt,
            OffsetDateTime retentionUntil) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime requestedAt = createdAt.minusMinutes(2);
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
                "b".repeat(64), UUID.randomUUID(), requestedAt.plusMinutes(1),
                "projection:" + runId, "c".repeat(64), requestedAt, actor,
                requestedAt, requestedAt.plusSeconds(30));
        UUID reportId = UUID.randomUUID();
        boolean published = "PUBLISHED".equals(state);
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_reports (
                    report_id, tenant_id, meeting_id, run_id, report_state, audience,
                    encrypted_payload, payload_sha256, source_sha256, schema_version,
                    retention_until, approved_at, approved_by, published_at, published_by,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, 1, ?, ?, ?, ?, 'ciphertext', ?, ?,
                        'meeting-intelligence-v1', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, reportId, meetingId, runId, state,
                published ? "MEETING_PARTICIPANTS" : "PRIVATE_REVIEWERS",
                "d".repeat(64), "a".repeat(64), retentionUntil,
                published ? createdAt.plusMinutes(1) : null,
                published ? actor : null, publishedAt, published ? actor : null,
                createdAt, actor, createdAt, actor);
        return reportId;
    }
}
