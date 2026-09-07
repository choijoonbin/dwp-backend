package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceAutoRequestModels.AutoRequest;
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

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class MeetingIntelligenceAutoRequestPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private MeetingIntelligenceAutoRequestRepository requests;
    private MeetingIntelligenceAutoRequestTransactions transactions;
    private MeetingIntelligenceAutoRequestProperties properties;
    private VideoMeetingIntelligenceService intelligenceService;
    private VideoMeetingAuditRecorder audit;
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
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VideoMeetingRepository meetings = new VideoMeetingRepository(jdbc, mapper);
        VideoMeetingContentRepository content = new VideoMeetingContentRepository(jdbc);
        VideoMeetingIntelligenceRepository intelligence =
                new VideoMeetingIntelligenceRepository(jdbc);
        MeetingTranscriptArtifactRepository artifacts =
                new MeetingTranscriptArtifactRepository(jdbc);
        requests = new MeetingIntelligenceAutoRequestRepository(jdbc);
        audit = new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-meeting-server", "auto-intelligence-test", "test"));
        fixture = governedFixture(meetings, content, intelligence, artifacts);
        properties = properties();
        transactions = transactional(new MeetingIntelligenceAutoRequestTransactions(
                requests, meetings, properties, audit,
                Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC)));
        intelligenceService = intelligenceService(
                meetings, content, intelligence, mapper, fixture.now());
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void workerCreatesOneGovernedDraftAndCommitsTerminalAudit() {
        MeetingIntelligenceAutoRequestWorker worker = worker(intelligenceService);

        assertThat(worker.dispatch()).isOne();

        AutoRequest completed = request();
        assertThat(completed.state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.SUCCEEDED);
        assertThat(completed.runId()).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_runs
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Integer.class, fixture.meetingId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_reports
                 WHERE tenant_id = 1 AND meeting_id = ? AND report_state = 'DRAFT'
                """, Integer.class, fixture.meetingId())).isOne();
        assertThat(autoAuditCount("meeting.intelligence.auto-request.completed")).isOne();
        String contentFree = jdbc.queryForObject("""
                SELECT row_to_json(request)::text
                  FROM vm_meeting_intelligence_auto_requests request
                 WHERE request_id = ?
                """, String.class, fixture.requestId());
        assertThat(contentFree).doesNotContain(
                "opaque/transcript/source", "provider-token", "transcript text");
    }

    @Test
    void unavailableReadinessReleasesPendingWithoutCreatingAFakeRun() {
        VideoMeetingIntelligenceService unavailable = mock(VideoMeetingIntelligenceService.class);
        doThrow(new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR, "dependencies unavailable"))
                .when(unavailable).ensureAutomaticExecutionReadiness(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());

        assertThat(worker(unavailable).dispatch()).isOne();

        AutoRequest pending = request();
        assertThat(pending.state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.PENDING);
        assertThat(pending.executionGeneration()).isEqualTo(2);
        assertThat(pending.intelligenceIdempotencyKey()).endsWith(":2");
        assertThat(pending.lastFailureCode()).isEqualTo("DEPENDENCIES_NOT_READY");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_runs", Integer.class)).isZero();
        assertThat(autoAuditCount("meeting.intelligence.auto-request.completed")).isZero();
    }

    @Test
    void transientProviderFailureRetriesWithANewGenerationThenCreatesOneReport() {
        VideoMeetingIntelligenceService flaky = intelligenceService(
                new FlakyProvider(), fixture.now());

        assertThat(worker(flaky).dispatch()).isOne();

        AutoRequest pending = request();
        assertThat(pending.state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.PENDING);
        assertThat(pending.executionGeneration()).isEqualTo(2);
        assertThat(pending.lastFailureCode()).isEqualTo("PROVIDER_EXECUTION_UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_intelligence_runs
                 WHERE run_state = 'FAILED'
                   AND failure_code = 'PROVIDER_EXECUTION_UNAVAILABLE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_reports", Integer.class)).isZero();
        jdbc.update("""
                UPDATE vm_meeting_intelligence_auto_requests
                   SET available_at = ? WHERE request_id = ?
                """, fixture.now(), fixture.requestId());

        assertThat(worker(flaky).dispatch()).isOne();

        assertThat(request().state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.SUCCEEDED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_runs", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_reports", Integer.class)).isOne();
        assertThat(autoAuditCount("meeting.intelligence.auto-request.completed")).isOne();
    }

    @Test
    void crashAfterRunCommitReclaimsSameGenerationAndFencesTheOldWorker() {
        AutoRequest first = transactions.claim();
        assertThat(transactions.claim()).isNull();
        MeetingRequestContext.set(fixture.subject());
        VideoMeetingIntelligenceDtos.RunResponse run;
        try {
            run = intelligenceService.createRun(
                    first.meetingId(), command(first),
                    first.intelligenceIdempotencyKey(), correlation(first));
        } finally {
            MeetingRequestContext.clear();
        }
        assertThat(run.state()).isEqualTo("SUCCEEDED");
        jdbc.update("""
                UPDATE vm_meeting_intelligence_auto_requests
                   SET lease_expires_at = ? WHERE request_id = ?
                """, fixture.now().minusSeconds(1), fixture.requestId());

        AutoRequest reclaimed = transactions.claim();

        assertThat(reclaimed.executionFence()).isNotEqualTo(first.executionFence());
        assertThat(reclaimed.executionGeneration()).isEqualTo(first.executionGeneration());
        assertThat(reclaimed.intelligenceIdempotencyKey())
                .isEqualTo(first.intelligenceIdempotencyKey());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThatThrownBy(() -> transactions.succeed(
                fixture.subject(), first, run.runId()))
                .isInstanceOf(BaseException.class);
        transactions.succeed(fixture.subject(), reclaimed, run.runId());
        assertThat(request().state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.SUCCEEDED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_runs", Integer.class)).isOne();
    }

    @Test
    void terminalAuditFailureRollsBackRequestAndReplayFinishesAfterLeaseReclaim() {
        jdbc.execute("""
                CREATE FUNCTION fail_auto_intelligence_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action'
                            = 'meeting.intelligence.auto-request.completed' THEN
                        RAISE EXCEPTION 'simulated auto intelligence audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_auto_intelligence_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_auto_intelligence_audit()
                """);

        assertThat(worker(intelligenceService).dispatch()).isOne();
        assertThat(request().state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.RUNNING);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_runs", Integer.class)).isOne();
        assertThat(autoAuditCount("meeting.intelligence.auto-request.completed")).isZero();
        jdbc.execute("DROP TRIGGER fail_auto_intelligence_audit_trigger ON sys_audit_outbox");
        jdbc.execute("DROP FUNCTION fail_auto_intelligence_audit()");
        jdbc.update("""
                UPDATE vm_meeting_intelligence_auto_requests
                   SET lease_expires_at = ? WHERE request_id = ?
                """, fixture.now().minusSeconds(1), fixture.requestId());

        assertThat(worker(intelligenceService).dispatch()).isOne();

        assertThat(request().state())
                .isEqualTo(MeetingIntelligenceAutoRequestModels.RequestState.SUCCEEDED);
        assertThat(request().attemptCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_runs", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_intelligence_reports", Integer.class)).isOne();
        assertThat(autoAuditCount("meeting.intelligence.auto-request.completed")).isOne();
    }

    private Fixture governedFixture(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingTranscriptArtifactRepository artifacts) {
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
        String consentHash = intelligence.consentEvidence(1, meetingId, noticeId)
                .snapshotSha256();
        long planVersion = content.plan(1, meetingId).orElseThrow().version();
        jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = 'BROKER',
                       object_key = 'opaque/transcript/source',
                       content_type = 'application/json', size_bytes = 1024,
                       sha256 = ?, retention_until = ?,
                       server_side_processing_allowed = TRUE,
                       processing_region = 'ap-northeast-2', content_notice_id = ?,
                       consent_snapshot_sha256 = ?,
                       registration_idempotency_key = 'auto-test-register',
                       registration_request_sha256 = ?, registered_at = ?,
                       registered_by = ?, transcript_plan_version = ?,
                       transcript_provider_code = 'TRANSCRIPT_BROKER',
                       transcript_storage_provider_code = 'BROKER',
                       finalization_idempotency_key = 'auto-test-finalize',
                       finalization_request_sha256 = ?, finalized_at = ?,
                       finalized_by = ?, updated_at = ?, updated_by = ?, version = version + 1
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_id = ?
                """, "a".repeat(64), now.plusDays(30), noticeId, consentHash,
                "b".repeat(64), now.minusSeconds(5), actor, planVersion,
                "c".repeat(64), now, actor, now, actor, meetingId, artifactId);
        var meeting = meetings.lockMeeting(1, meetingId);
        var plan = content.plan(1, meetingId).orElseThrow();
        var notice = content.notice(1, meetingId, noticeId).orElseThrow();
        var artifact = artifacts.lock(1, meetingId, artifactId).orElseThrow();
        UUID requestId = requests.enqueue(meeting, plan, notice, artifact, now);
        var subject = new MeetingRequestContext.Subject(
                actor, 1, personId, "Meeting automatic intelligence",
                Set.of("SYSTEM_AUTO_INTELLIGENCE"), Set.of(), Set.of());
        return new Fixture(meetingId, artifactId, requestId, actor, now, subject);
    }

    private VideoMeetingIntelligenceService intelligenceService(
            VideoMeetingRepository meetings,
            VideoMeetingContentRepository content,
            VideoMeetingIntelligenceRepository intelligence,
            ObjectMapper mapper,
            OffsetDateTime now) {
        MeetingTranscriptSource transcripts = new FakeTranscriptSource();
        MeetingIntelligencePayloadProtector protector = new FakeProtector();
        MeetingContentDependencies dependencies = () ->
                new MeetingContentDependencies.Status(false, true, true, false, true, true);
        MeetingIntelligenceRetentionService retention =
                mock(MeetingIntelligenceRetentionService.class);
        when(retention.ready()).thenReturn(true);
        var runTransactions = transactional(new MeetingIntelligenceRunTransactions(
                meetings, content, intelligence, new MeetingContentAccessPolicy(),
                dependencies, retention, transcripts, protector, audit));
        return transactional(new VideoMeetingIntelligenceService(
                meetings, intelligence, new FakeProvider(), transcripts, protector,
                new MeetingIntelligenceOutputValidator(), new MeetingContentAccessPolicy(),
                runTransactions, audit, mapper,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)));
    }

    private VideoMeetingIntelligenceService intelligenceService(
            MeetingIntelligenceProvider provider, OffsetDateTime now) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VideoMeetingRepository meetings = new VideoMeetingRepository(jdbc, mapper);
        VideoMeetingContentRepository content = new VideoMeetingContentRepository(jdbc);
        VideoMeetingIntelligenceRepository intelligence =
                new VideoMeetingIntelligenceRepository(jdbc);
        MeetingTranscriptSource transcripts = new FakeTranscriptSource();
        MeetingIntelligencePayloadProtector protector = new FakeProtector();
        MeetingContentDependencies dependencies = () ->
                new MeetingContentDependencies.Status(false, true, true, false, true, true);
        MeetingIntelligenceRetentionService retention =
                mock(MeetingIntelligenceRetentionService.class);
        when(retention.ready()).thenReturn(true);
        var runTransactions = transactional(new MeetingIntelligenceRunTransactions(
                meetings, content, intelligence, new MeetingContentAccessPolicy(),
                dependencies, retention, transcripts, protector, audit));
        return transactional(new VideoMeetingIntelligenceService(
                meetings, intelligence, provider, transcripts, protector,
                new MeetingIntelligenceOutputValidator(), new MeetingContentAccessPolicy(),
                runTransactions, audit, mapper,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)));
    }

    private MeetingIntelligenceAutoRequestWorker worker(
            VideoMeetingIntelligenceService service) {
        return new MeetingIntelligenceAutoRequestWorker(
                transactions, requests, service, properties);
    }

    private MeetingIntelligenceAutoRequestProperties properties() {
        MeetingIntelligenceAutoRequestProperties value =
                new MeetingIntelligenceAutoRequestProperties();
        value.setEnabled(true);
        return value;
    }

    private AutoRequest request() {
        return requests.byId(fixture.requestId()).orElseThrow();
    }

    private VideoMeetingIntelligenceDtos.CreateRunCommand command(AutoRequest request) {
        return new VideoMeetingIntelligenceDtos.CreateRunCommand(
                request.sourceArtifactId(), request.outputLanguage(),
                request.expectedContentPlanVersion());
    }

    private String correlation(AutoRequest request) {
        return "meeting-auto-intelligence:" + request.requestId();
    }

    private int autoAuditCount(String action) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = ?
                   AND payload ->> 'targetId' = ?
                """, Integer.class, action, fixture.requestId().toString());
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    private record Fixture(
            UUID meetingId,
            UUID artifactId,
            UUID requestId,
            long actor,
            OffsetDateTime now,
            MeetingRequestContext.Subject subject) {
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
            CitedText summary = new CitedText(
                    "The team agreed on the release gate.", List.of(citation));
            return new Analysis(
                    summary, List.of(), List.of(), List.of(), List.of(), List.of(),
                    new ConversationClimate(
                            ClimateLabel.ALIGNED,
                            List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT),
                            List.of(citation)));
        }
    }

    private static final class FlakyProvider implements MeetingIntelligenceProvider {
        private final AtomicInteger attempts = new AtomicInteger();
        private final FakeProvider delegate = new FakeProvider();

        @Override public Capability capability(ExecutionContext context) {
            return delegate.capability(context);
        }

        @Override public Analysis analyze(ExecutionContext context, Request request) {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("transient managed provider outage");
            }
            return delegate.analyze(context, request);
        }
    }
}
