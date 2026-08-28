package com.dwp.services.meeting.videomeeting.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MeetingIntelligenceRetentionPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private VideoMeetingIntelligenceRepository repository;

    @BeforeEach
    void migrate() {
        dataSource = new PGSimpleDataSource();
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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new VideoMeetingIntelligenceRepository(jdbc);
    }

    @Test
    void failureThenCleanEmptyPollRecoversReadinessWithoutCompetingLease() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID failedFence = UUID.randomUUID();
        UUID contenderFence = UUID.randomUUID();

        assertThat(attempt(now, failedFence)).isTrue();
        assertThat(attempt(now.plusSeconds(1), contenderFence)).isFalse();
        transaction.executeWithoutResult(status -> repository.markRetentionFailure(
                now.plusSeconds(2), failedFence, "RETENTION_PURGE_FAILED"));

        UUID recoveryFence = UUID.randomUUID();
        assertThat(attempt(now.plusSeconds(3), recoveryFence)).isTrue();
        var result = transaction.execute(status -> repository.purgeExpiredReports(
                now.plusSeconds(3), 100, "retention-worker-b", recoveryFence));
        assertThat(result.deletedCount()).isZero();
        assertThat(result.overdueRemaining()).isFalse();
        transaction.executeWithoutResult(status -> repository.markRetentionSuccess(
                now.plusSeconds(4), recoveryFence, true));

        assertThat(repository.retentionHealth().orElseThrow().lastFailureAt()).isNull();
        assertThat(serviceAt(now.plusSeconds(5)).ready()).isTrue();
    }

    @Test
    void lockedOverdueReportPreventsEmptyWinnerFromMaskingFailure() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID reportId = expiredReport(now, false);
        UUID failedFence = UUID.randomUUID();
        assertThat(attempt(now, failedFence)).isTrue();
        transaction.executeWithoutResult(status -> repository.markRetentionFailure(
                now.plusSeconds(1), failedFence, "RETENTION_PURGE_FAILED"));
        UUID recoveryFence = UUID.randomUUID();
        assertThat(attempt(now.plusSeconds(2), recoveryFence)).isTrue();

        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("""
                    SELECT report_id FROM vm_meeting_intelligence_reports
                     WHERE report_id = ? FOR UPDATE
                    """)) {
                statement.setObject(1, reportId);
                statement.executeQuery();
            }
            var result = transaction.execute(status -> repository.purgeExpiredReports(
                    now.plusSeconds(3), 100, "retention-worker-b", recoveryFence));
            assertThat(result.deletedCount()).isZero();
            assertThat(result.overdueRemaining()).isTrue();
            transaction.executeWithoutResult(status -> repository.markRetentionSuccess(
                    now.plusSeconds(4), recoveryFence, false));
            lock.rollback();
        }

        assertThat(repository.retentionHealth().orElseThrow().lastFailureCode())
                .isEqualTo("RETENTION_PURGE_FAILED");
        assertThat(serviceAt(now.plusSeconds(5)).ready()).isFalse();
    }

    @Test
    void purgeShredsOnlyExpiredNonHeldCiphertextAndWritesDeletionEvidence() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID purgeable = expiredReport(now, false);
        UUID held = expiredReport(now, true);
        UUID fence = UUID.randomUUID();
        assertThat(attempt(now, fence)).isTrue();

        var result = transaction.execute(status -> repository.purgeExpiredReports(
                now, 100, "retention-worker-a", fence));

        assertThat(result.deletedCount()).isOne();
        assertThat(result.overdueRemaining()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT report_state FROM vm_meeting_intelligence_reports
                 WHERE report_id = ?
                """, String.class, purgeable)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT encrypted_payload IS NULL AND payload_sha256 IS NULL
                  FROM vm_meeting_intelligence_reports WHERE report_id = ?
                """, Boolean.class, purgeable)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_deletions
                 WHERE report_id = ? AND fence_token = ?
                """, Integer.class, purgeable, fence)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT report_state FROM vm_meeting_intelligence_reports
                 WHERE report_id = ?
                """, String.class, held)).isEqualTo("DRAFT");
    }

    @Test
    void healthyFutureLeaseDoesNotDropReadinessButExpiredLeaseDoes() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE vm_meeting_intelligence_retention_health
                   SET last_attempt_at = ?, last_success_at = ?
                 WHERE health_key = 'REPORT_RETENTION'
                """, now.minusMinutes(1), now.minusMinutes(1));
        UUID active = UUID.randomUUID();
        assertThat(attempt(now, active)).isTrue();
        assertThat(serviceAt(now.plusSeconds(10)).ready()).isTrue();

        assertThat(serviceAt(now.plusMinutes(2)).ready()).isFalse();
        UUID reclaimed = UUID.randomUUID();
        assertThat(attempt(now.plusMinutes(2), reclaimed)).isTrue();
        assertThat(repository.retentionHealth().orElseThrow().lastFailureCode())
                .isEqualTo("RETENTION_LEASE_EXPIRED");
    }

    @Test
    void reclaimedFenceRejectsStaleWorkerPurgeAndTerminal() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID stale = UUID.randomUUID();
        assertThat(attempt(now, stale)).isTrue();
        UUID winner = UUID.randomUUID();
        assertThat(attempt(now.plusMinutes(2), winner)).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transaction.execute(status -> repository.purgeExpiredReports(
                        now.plusMinutes(2), 100, "retention-worker-a", stale)))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transaction.executeWithoutResult(status -> repository.markRetentionSuccess(
                        now.plusMinutes(2), stale, true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.retentionHealth().orElseThrow().activeFence())
                .isEqualTo(winner);
    }

    private boolean attempt(OffsetDateTime at, UUID fence) {
        return Boolean.TRUE.equals(transaction.execute(status ->
                repository.tryMarkRetentionAttempt(at, at.plusMinutes(1), fence)));
    }

    private MeetingIntelligenceRetentionService serviceAt(OffsetDateTime at) {
        MeetingIntelligenceRetentionProperties properties =
                new MeetingIntelligenceRetentionProperties();
        properties.setEnabled(true);
        properties.setPollDelay(Duration.ofMinutes(5));
        properties.setLeaseDuration(Duration.ofMinutes(1));
        properties.setWorkerId("retention-worker-a");
        return new MeetingIntelligenceRetentionService(
                repository, new MeetingIntelligenceRetentionTransactions(repository),
                properties, Clock.fixed(at.toInstant(), ZoneOffset.UTC));
    }

    private UUID expiredReport(OffsetDateTime now, boolean legalHold) {
        UUID meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'ENDED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        Long actor = jdbc.queryForObject("""
                SELECT organizer_user_id FROM vm_meetings
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                """, UUID.class, meetingId);
        Integer revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(notice_revision), 0) + 1
                  FROM vm_meeting_content_notices
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Integer.class, meetingId);
        UUID noticeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, ?, FALSE, TRUE, TRUE, ?)
                """, noticeId, meetingId, revision, actor);
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
                "b".repeat(64), UUID.randomUUID(), now.plusMinutes(1),
                "retention:" + runId, "c".repeat(64), now.minusDays(2), actor,
                now.minusDays(2), now.minusDays(2).plusMinutes(1));
        UUID reportId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_reports (
                    report_id, tenant_id, meeting_id, run_id, report_state, audience,
                    encrypted_payload, payload_sha256, source_sha256, schema_version,
                    retention_until, legal_hold, created_at, created_by, updated_at, updated_by)
                VALUES (?, 1, ?, ?, 'DRAFT', 'PRIVATE_REVIEWERS', 'ciphertext', ?, ?,
                        'meeting-intelligence-v1', ?, ?, ?, ?, ?, ?)
                """, reportId, meetingId, runId, "d".repeat(64), "a".repeat(64),
                now.minusDays(1), legalHold, now.minusDays(2), actor,
                now.minusDays(2), actor);
        return reportId;
    }
}
