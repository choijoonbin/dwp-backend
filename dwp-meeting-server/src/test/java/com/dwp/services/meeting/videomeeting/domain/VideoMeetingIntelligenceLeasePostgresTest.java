package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class VideoMeetingIntelligenceLeasePostgresTest {

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
    void reclaimKeepsRunIdAndFencesTheStaleWorker() {
        UUID runId = expiredRun();
        var stale = repository.run(1, meetingId(runId), runId).orElseThrow();
        UUID recoveredFence = UUID.randomUUID();
        OffsetDateTime recoveredAt = OffsetDateTime.now(ZoneOffset.UTC);

        var recovered = transaction.execute(status -> repository.reclaimExpired(
                stale, recoveredFence, recoveredAt, recoveredAt.plusMinutes(2)))
                .orElseThrow();

        assertThat(recovered.runId()).isEqualTo(stale.runId());
        assertThat(recovered.executionFence()).isEqualTo(recoveredFence);
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(recovered.version()).isEqualTo(1);
        assertThatThrownBy(() -> transaction.execute(status -> repository.fail(
                stale, "STALE_WORKER", recoveredAt.plusSeconds(1))))
                .isInstanceOf(BaseException.class);

        var succeeded = transaction.execute(status -> repository.succeed(
                recovered, "agent", "model-v1", recoveredAt.plusSeconds(1)));
        assertThat(succeeded.state()).isEqualTo(
                VideoMeetingIntelligenceModels.RunState.SUCCEEDED);
        assertThat(succeeded.providerCode()).isEqualTo("agent");
    }

    @Test
    void onlyOneConcurrentWorkerCanReclaimAnExpiredRun() throws Exception {
        UUID runId = expiredRun();
        UUID meetingId = meetingId(runId);
        var stale = repository.run(1, meetingId, runId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> reclaim(
                    stale, UUID.randomUUID(), now, ready, start));
            Future<Boolean> second = executor.submit(() -> reclaim(
                    stale, UUID.randomUUID(), now, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
            var winner = repository.run(1, meetingId, runId).orElseThrow();
            assertThat(winner.runId()).isEqualTo(runId);
            assertThat(winner.attemptCount()).isEqualTo(2);
            assertThat(winner.version()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void terminalTransitionRejectsAnExpiredLeaseEvenWithCurrentFence() {
        UUID runId = expiredRun();
        UUID meetingId = meetingId(runId);
        var expired = repository.run(1, meetingId, runId).orElseThrow();

        assertThatThrownBy(() -> transaction.execute(status -> repository.fail(
                expired, "LEASE_EXPIRED", OffsetDateTime.now(ZoneOffset.UTC))))
                .isInstanceOf(BaseException.class);
        assertThat(repository.run(1, meetingId, runId).orElseThrow().state())
                .isEqualTo(VideoMeetingIntelligenceModels.RunState.RUNNING);
    }

    private boolean reclaim(
            VideoMeetingIntelligenceModels.IntelligenceRun stale,
            UUID fence,
            OffsetDateTime now,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        TransactionTemplate isolated = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        VideoMeetingIntelligenceRepository contender =
                new VideoMeetingIntelligenceRepository(new JdbcTemplate(dataSource));
        return isolated.execute(status -> contender.reclaimExpired(
                stale, fence, now, now.plusMinutes(2)).isPresent());
    }

    private UUID expiredRun() {
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
        UUID noticeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, 99, FALSE, TRUE, TRUE, ?)
                """, noticeId, meetingId, actor);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_intelligence_runs (
                    run_id, tenant_id, meeting_id, source_artifact_id, source_sha256,
                    content_notice_id, consent_snapshot_sha256, analysis_profile,
                    output_language, processing_region, execution_fence,
                    lease_expires_at, attempt_count, run_state, provider_code,
                    provider_model, prompt_version, schema_version, idempotency_key,
                    request_sha256, requested_at, requested_by, started_at)
                VALUES (?, 1, ?, ?, ?, ?, ?, 'STANDARD_RECAP_V1', 'ko-KR',
                        'ap-northeast-2', ?, CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        1, 'RUNNING', 'PENDING', 'PENDING', 'governed-recap-v1',
                        'meeting-intelligence-v1', ?, ?,
                        CURRENT_TIMESTAMP - INTERVAL '3 minutes', ?,
                        CURRENT_TIMESTAMP - INTERVAL '3 minutes')
                """, runId, meetingId, artifactId, "a".repeat(64), noticeId,
                "b".repeat(64), UUID.randomUUID(), "lease:" + runId,
                "c".repeat(64), actor);
        RUN_MEETINGS.put(runId, meetingId);
        return runId;
    }

    private UUID meetingId(UUID runId) {
        return RUN_MEETINGS.get(runId);
    }

    private static final java.util.concurrent.ConcurrentMap<UUID, UUID> RUN_MEETINGS =
            new java.util.concurrent.ConcurrentHashMap<>();
}
