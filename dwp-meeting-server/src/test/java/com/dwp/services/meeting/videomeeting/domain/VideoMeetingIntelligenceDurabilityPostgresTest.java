package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class VideoMeetingIntelligenceDurabilityPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private ObjectMapper mapper;
    private VideoMeetingRepository meetings;
    private VideoMeetingIntelligenceRepository intelligence;
    private MeetingIntelligenceRunTransactions runs;
    private VideoMeetingIntelligenceService service;
    private Fixture fixture;

    @BeforeEach
    void setup() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        meetings = new VideoMeetingRepository(jdbc, mapper);
        intelligence = new VideoMeetingIntelligenceRepository(jdbc);
        fixture = governedFixture();
        wireRuntime();
        MeetingRequestContext.set(fixture.subject());
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void durablePrepareCommitsRunningEvidenceBeforeAWorkerCrash() {
        var prepared = prepare("durability-key-0001", fixture.now());

        assertThat(prepared.execute()).isTrue();
        JdbcTemplate independent = new JdbcTemplate(dataSource);
        assertThat(independent.queryForObject("""
                SELECT run_state FROM vm_meeting_intelligence_runs WHERE run_id = ?
                """, String.class, prepared.run().runId())).isEqualTo("RUNNING");
        assertThat(independent.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_reports WHERE run_id = ?
                """, Integer.class, prepared.run().runId())).isZero();
    }

    @Test
    void serviceCommitsDraftTerminalAndCanonicalAuditTogether() {
        var response = service.createRun(
                fixture.meetingId(), fixture.command(),
                "durability-key-0002", "corr-durable-success");

        assertThat(response.state()).isEqualTo("SUCCEEDED");
        assertThat(response.reportId()).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_reports
                 WHERE run_id = ? AND report_state = 'DRAFT'
                """, Integer.class, response.runId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.intelligence.completed'
                   AND payload ->> 'targetId' = ?
                """, Integer.class, response.runId().toString())).isOne();
    }

    @Test
    void lateProviderSuccessAfterPlanMutationClosesFailedWithTerminalAudit() {
        var prepared = prepare("durability-key-late-plan", fixture.now());
        jdbc.update("""
                UPDATE vm_meeting_content_plans
                   SET ai_summary_requested = FALSE, plan_state = 'READY',
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, fixture.now().plusSeconds(1), fixture.meetingId());

        var finalized = runs.succeed(
                fixture.subject(), prepared, "corr-late-plan", "agent", "model-v1",
                UUID.randomUUID(), "ciphertext", "e".repeat(64),
                prepared.retentionUntil(), fixture.actor(), fixture.now().plusSeconds(2));

        assertThat(finalized.run().state())
                .isEqualTo(VideoMeetingIntelligenceModels.RunState.FAILED);
        assertThat(finalized.run().failureCode()).isEqualTo("GOVERNANCE_POLICY_CHANGED");
        assertThat(finalized.report()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_reports WHERE run_id = ?
                """, Integer.class, prepared.run().runId())).isZero();
        assertThat(auditCount(prepared.run().runId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'action' FROM sys_audit_outbox
                 WHERE payload ->> 'targetId' = ?
                """, String.class, prepared.run().runId().toString()))
                .isEqualTo("meeting.intelligence.failed");
    }

    @Test
    void expiredLeaseFencesLateSuccessAndRollsBackDraftAndAudit() {
        var prepared = prepare("durability-key-expired-success", fixture.now());
        jdbc.update("""
                UPDATE vm_meeting_intelligence_runs
                   SET lease_expires_at = ? WHERE run_id = ?
                """, fixture.now().plusSeconds(1), prepared.run().runId());

        assertThatThrownBy(() -> runs.succeed(
                fixture.subject(), prepared, "corr-expired-success", "agent", "model-v1",
                UUID.randomUUID(), "ciphertext", "e".repeat(64),
                prepared.retentionUntil(), fixture.actor(), fixture.now().plusSeconds(2)))
                .isInstanceOf(RuntimeException.class);

        assertRunningWithoutReportOrAudit(prepared.run().runId());
    }

    @Test
    void expiredLeaseFencesGovernanceFailureAndRollsBackTerminalAudit() {
        var prepared = prepare("durability-key-expired-policy", fixture.now());
        jdbc.update("""
                UPDATE vm_meeting_content_plans
                   SET ai_summary_requested = FALSE, version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, fixture.now().plusSeconds(1), fixture.meetingId());
        jdbc.update("""
                UPDATE vm_meeting_intelligence_runs
                   SET lease_expires_at = ? WHERE run_id = ?
                """, fixture.now().plusSeconds(1), prepared.run().runId());

        assertThatThrownBy(() -> runs.succeed(
                fixture.subject(), prepared, "corr-expired-policy", "agent", "model-v1",
                UUID.randomUUID(), "ciphertext", "e".repeat(64),
                prepared.retentionUntil(), fixture.actor(), fixture.now().plusSeconds(2)))
                .isInstanceOf(RuntimeException.class);

        assertRunningWithoutReportOrAudit(prepared.run().runId());
    }

    @Test
    void reclaimedWorkerFencesStaleFinalizeWithoutReportOrAudit() {
        var stale = prepare("durability-key-0003", fixture.now());
        jdbc.update("""
                UPDATE vm_meeting_intelligence_runs
                   SET lease_expires_at = ? WHERE run_id = ?
                """, fixture.now().plusSeconds(1), stale.run().runId());
        var reclaimed = prepare("durability-key-0003", fixture.now().plusSeconds(2));
        assertThat(reclaimed.execute()).isTrue();
        assertThat(reclaimed.run().runId()).isEqualTo(stale.run().runId());

        assertThatThrownBy(() -> runs.succeed(
                fixture.subject(), stale, "corr-stale",
                "agent", "model-v1", UUID.randomUUID(), "ciphertext",
                "e".repeat(64), stale.retentionUntil(), fixture.actor(),
                fixture.now().plusSeconds(3)))
                .isInstanceOf(RuntimeException.class);
        assertThat(intelligence.reportForRun(
                1, fixture.meetingId(), stale.run().runId())).isEmpty();
        assertThat(intelligence.run(1, fixture.meetingId(), stale.run().runId())
                .orElseThrow().state())
                .isEqualTo(VideoMeetingIntelligenceModels.RunState.RUNNING);
        assertThat(auditCount(stale.run().runId())).isZero();
    }

    @Test
    void auditInsertFailureRollsBackReportAndTerminalProjection() {
        jdbc.execute("""
                CREATE FUNCTION fail_intelligence_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.intelligence.completed' THEN
                        RAISE EXCEPTION 'simulated audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_intelligence_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_intelligence_audit()
                """);

        assertThatThrownBy(() -> service.createRun(
                fixture.meetingId(), fixture.command(),
                "durability-key-0004", "corr-audit-failure"))
                .isInstanceOf(RuntimeException.class);

        UUID runId = jdbc.queryForObject("""
                SELECT run_id FROM vm_meeting_intelligence_runs
                 WHERE idempotency_key = 'durability-key-0004'
                """, UUID.class);
        assertThat(jdbc.queryForObject("""
                SELECT run_state FROM vm_meeting_intelligence_runs WHERE run_id = ?
                """, String.class, runId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_reports WHERE run_id = ?
                """, Integer.class, runId)).isZero();
        assertThat(auditCount(runId)).isZero();
    }

    @Test
    void failedTerminalAuditInsertFailureRollsBackGovernanceFailureProjection() {
        var prepared = prepare("durability-key-failed-audit", fixture.now());
        jdbc.update("""
                UPDATE vm_meeting_content_plans
                   SET ai_summary_requested = FALSE, version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, fixture.now().plusSeconds(1), fixture.meetingId());
        jdbc.execute("""
                CREATE FUNCTION fail_intelligence_failed_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.intelligence.failed' THEN
                        RAISE EXCEPTION 'simulated failed audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_intelligence_failed_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_intelligence_failed_audit()
                """);

        assertThatThrownBy(() -> runs.succeed(
                fixture.subject(), prepared, "corr-failed-audit", "agent", "model-v1",
                UUID.randomUUID(), "ciphertext", "e".repeat(64),
                prepared.retentionUntil(), fixture.actor(), fixture.now().plusSeconds(2)))
                .isInstanceOf(RuntimeException.class);

        assertRunningWithoutReportOrAudit(prepared.run().runId());
    }

    @Test
    void requesterCannotReviewOwnDraftButASecondHostCan() {
        var created = service.createRun(
                fixture.meetingId(), fixture.command(),
                "durability-key-0005", "corr-sod");

        assertThat(service.report(fixture.meetingId(), created.reportId())
                .canCurrentViewerReview()).isFalse();
        long secondHost = jdbc.queryForObject("""
                SELECT user_id FROM vm_meeting_participants
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id <> ?
                 ORDER BY user_id LIMIT 1
                """, Long.class, fixture.meetingId(), fixture.actor());
        jdbc.update("""
                UPDATE vm_meeting_participants
                   SET participant_role = 'CO_HOST'
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id = ?
                """, fixture.meetingId(), secondHost);
        UUID personId = jdbc.queryForObject("""
                SELECT person_public_id FROM vm_meeting_participants
                 WHERE tenant_id = 1 AND meeting_id = ? AND user_id = ?
                """, UUID.class, fixture.meetingId(), secondHost);
        MeetingRequestContext.set(new MeetingRequestContext.Subject(
                secondHost, 1, personId, "Second host", Set.of("USER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of()));

        assertThat(service.report(fixture.meetingId(), created.reportId())
                .canCurrentViewerReview()).isTrue();
    }

    @Test
    void reloadWithADifferentKeyReusesTheSameActiveSourceRun() {
        var first = prepare("durability-key-0006", fixture.now());
        var reload = prepare("durability-key-0007", fixture.now().plusSeconds(1));

        assertThat(first.execute()).isTrue();
        assertThat(reload.execute()).isFalse();
        assertThat(reload.run().runId()).isEqualTo(first.run().runId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_runs
                 WHERE tenant_id = 1 AND meeting_id = ?
                   AND source_artifact_id = ? AND run_state = 'RUNNING'
                """, Integer.class, fixture.meetingId(), fixture.command().sourceArtifactId()))
                .isOne();
    }

    private MeetingIntelligenceRunTransactions.PreparedExecution prepare(
            String idempotencyKey, OffsetDateTime now) {
        return runs.prepare(
                fixture.subject(), fixture.meetingId(), fixture.command(), idempotencyKey,
                "f".repeat(64), UUID.randomUUID(), now);
    }

    private void wireRuntime() {
        MeetingTranscriptSource transcript = new FakeTranscriptSource();
        MeetingIntelligencePayloadProtector protector = new FakeProtector();
        MeetingContentDependencies dependencies = () ->
                new MeetingContentDependencies.Status(false, true, true, false, true, true);
        MeetingIntelligenceRetentionService retention =
                mock(MeetingIntelligenceRetentionService.class);
        when(retention.ready()).thenReturn(true);
        VideoMeetingAuditRecorder audit = new VideoMeetingAuditRecorder(
                new AuditOutboxRecorder(
                        new NamedParameterJdbcTemplate(jdbc), mapper,
                        "dwp-meeting-server", "durability-test", "test"));
        var target = new MeetingIntelligenceRunTransactions(
                meetings, new VideoMeetingContentRepository(jdbc), intelligence,
                new MeetingContentAccessPolicy(), dependencies, retention,
                transcript, protector, audit);
        runs = transactional(target);
        service = new VideoMeetingIntelligenceService(
                meetings, intelligence, new FakeProvider(), transcript, protector,
                new MeetingIntelligenceOutputValidator(), new MeetingContentAccessPolicy(),
                runs, audit, mapper,
                Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC));
    }

    private Fixture governedFixture() {
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
        UUID personId = jdbc.queryForObject("""
                SELECT organizer_person_public_id FROM vm_meetings
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, UUID.class, meetingId);
        UUID noticeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, 1, TRUE, TRUE, TRUE, ?)
                """, noticeId, meetingId, actor);
        jdbc.update("""
                UPDATE vm_meeting_content_plans
                   SET recording_requested = TRUE, transcription_requested = TRUE,
                       ai_summary_requested = TRUE, e2ee_enabled = FALSE,
                       plan_state = 'READY', current_notice_id = ?, notice_revision = 1,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, noticeId, now, actor, meetingId);
        jdbc.update("""
                UPDATE vm_tenant_policies
                   SET recording_policy = 'HOST_OPT_IN', artifact_retention_days = 30
                 WHERE tenant_id = 1
                """);
        jdbc.update("""
                INSERT INTO vm_meeting_content_notice_acknowledgements (
                    acknowledgement_id, tenant_id, meeting_id, notice_id,
                    participant_id, acknowledged_by, acknowledged_at)
                SELECT gen_random_uuid(), tenant_id, meeting_id, ?, participant_id,
                       user_id, ? FROM vm_meeting_participants
                 WHERE tenant_id = 1 AND meeting_id = ?
                   AND attendance_state IN ('ADMITTED', 'JOINED', 'LEFT')
                """, noticeId, now, meetingId);
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                """, UUID.class, meetingId);
        var consent = intelligence.consentEvidence(1, meetingId, noticeId);
        jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = 'BROKER',
                       object_key = 'opaque/transcript', content_type = 'application/json',
                       size_bytes = 1024, sha256 = ?, retention_until = ?,
                       server_side_processing_allowed = TRUE,
                       processing_region = 'ap-northeast-2', content_notice_id = ?,
                       consent_snapshot_sha256 = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_id = ?
                """, "a".repeat(64), now.plusDays(30), noticeId,
                consent.snapshotSha256(), now, actor, meetingId, artifactId);
        long planVersion = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_content_plans
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        var subject = new MeetingRequestContext.Subject(
                actor, 1, personId, "Meeting host", Set.of("USER"),
                Set.of("APP.MEETINGS:UPDATE"), Set.of());
        return new Fixture(
                meetingId, artifactId, noticeId, actor, now, subject,
                new VideoMeetingIntelligenceDtos.CreateRunCommand(
                        artifactId, "ko-KR", planVersion));
    }

    private int auditCount(UUID runId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'targetId' = ?
                """, Integer.class, runId.toString());
    }

    private void assertRunningWithoutReportOrAudit(UUID runId) {
        assertThat(jdbc.queryForObject("""
                SELECT run_state FROM vm_meeting_intelligence_runs WHERE run_id = ?
                """, String.class, runId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_reports WHERE run_id = ?
                """, Integer.class, runId)).isZero();
        assertThat(auditCount(runId)).isZero();
    }

    private MeetingIntelligenceRunTransactions transactional(
            MeetingIntelligenceRunTransactions target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return (MeetingIntelligenceRunTransactions) proxy.getProxy();
    }

    private record Fixture(
            UUID meetingId,
            UUID artifactId,
            UUID noticeId,
            long actor,
            OffsetDateTime now,
            MeetingRequestContext.Subject subject,
            VideoMeetingIntelligenceDtos.CreateRunCommand command) {
    }

    private static final class FakeTranscriptSource implements MeetingTranscriptSource {
        @Override public boolean available() { return true; }
        @Override public List<MeetingIntelligenceProvider.TranscriptSegment> read(
                ReadContext context) {
            return List.of(new MeetingIntelligenceProvider.TranscriptSegment(
                    "s1", 0, 1_000, "The team agreed on the governed release gate."));
        }
    }

    private static final class FakeProtector implements MeetingIntelligencePayloadProtector {
        @Override public boolean available() { return true; }
        @Override public boolean ready() { return true; }
        @Override public String protect(long tenantId, UUID reportId, byte[] plaintext) {
            return Base64.getEncoder().encodeToString(plaintext);
        }
        @Override public byte[] unprotect(long tenantId, UUID reportId, String payload) {
            return Base64.getDecoder().decode(payload);
        }
    }

    private static final class FakeProvider implements MeetingIntelligenceProvider {
        @Override public Capability capability(ExecutionContext context) {
            return new Capability(
                    true, "agent", "model-v1", "ap-northeast-2",
                    true, true, List.of(VideoMeetingIntelligenceModels.SCHEMA_VERSION));
        }
        @Override public Analysis analyze(ExecutionContext context, Request request) {
            Citation citation = new Citation("s1", 0, 900);
            CitedText summary = new CitedText("The team agreed on the release gate.",
                    List.of(citation));
            return new Analysis(
                    summary, List.of(), List.of(), List.of(), List.of(), List.of(),
                    new ConversationClimate(
                            ClimateLabel.ALIGNED,
                            List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT),
                            List.of(citation)));
        }
    }
}
