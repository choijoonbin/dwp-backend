package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingContentDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.Preparation;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class MeetingRecordingCommandDurabilityPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private VideoMeetingRepository meetings;
    private VideoMeetingContentRepository content;
    private MeetingRecordingCommandRepository commands;
    private MeetingRecordingHttpProperties properties;
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
        meetings = new VideoMeetingRepository(jdbc, mapper);
        content = new VideoMeetingContentRepository(jdbc);
        commands = new MeetingRecordingCommandRepository(jdbc);
        properties = new MeetingRecordingHttpProperties();
        properties.setCommandLease(Duration.ofMinutes(2));
        audit = new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-meeting-server", "recording-durability-test", "test"));
        fixture = fixture();
        MeetingRequestContext.set(fixture.subject());
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void providerIoRunsOutsideTransactionAndStartStopCommitFencedState() {
        AtomicBoolean providerObservedTransaction = new AtomicBoolean();
        MeetingRecordingProvider provider = mock(MeetingRecordingProvider.class);
        when(provider.capability()).thenReturn(recordingCapability());
        when(provider.start(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            providerObservedTransaction.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            MeetingRecordingProvider.Command command = invocation.getArgument(0);
            return new MeetingRecordingProvider.Receipt(
                    command.recordingSessionId(), "STARTED", "provider-start-001");
        });
        when(provider.stop(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            providerObservedTransaction.set(providerObservedTransaction.get()
                    || TransactionSynchronizationManager.isActualTransactionActive());
            MeetingRecordingProvider.Command command = invocation.getArgument(0);
            return new MeetingRecordingProvider.Receipt(
                    command.recordingSessionId(), "STOPPED", "provider-stop-001");
        });
        MeetingMediaProvider media = mock(MeetingMediaProvider.class);
        when(media.capability()).thenReturn(mediaCapability());
        MeetingContentDependencies dependencies = () -> dependencies();
        VideoMeetingRecordingService service = new VideoMeetingRecordingService(
                transactionsAt(fixture.now()), provider, media, dependencies);

        var started = service.requestRecording(
                fixture.meetingId(),
                new VideoMeetingContentDtos.RequestRecordingCommand(fixture.planVersion()),
                "recording-start-0001", "corr-recording-start");
        long sessionVersion = started.response().recordingSession().version();
        var stopped = service.stopRecording(
                fixture.meetingId(),
                new VideoMeetingContentDtos.StopRecordingCommand(sessionVersion),
                "recording-stop-0001", "corr-recording-stop");

        assertThat(providerObservedTransaction).isFalse();
        assertThat(started.response().commandState()).isEqualTo("RECORDING");
        assertThat(stopped.response().commandState()).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_recording_provider_commands
                 WHERE tenant_id = 1 AND meeting_id = ? AND command_state = 'SUCCEEDED'
                """, Integer.class, fixture.meetingId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' IN (
                    'meeting.recording.started', 'meeting.recording.stopped')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void retentionReadinessBlocksNewStartButNeverPreventsAProviderBoundStop() {
        AtomicBoolean retentionReady = new AtomicBoolean(true);
        MeetingRecordingProvider provider = mock(MeetingRecordingProvider.class);
        when(provider.capability()).thenReturn(recordingCapability());
        when(provider.start(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            MeetingRecordingProvider.Command command = invocation.getArgument(0);
            return new MeetingRecordingProvider.Receipt(
                    command.recordingSessionId(), "STARTED", "provider-start-retention");
        });
        when(provider.stop(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            MeetingRecordingProvider.Command command = invocation.getArgument(0);
            return new MeetingRecordingProvider.Receipt(
                    command.recordingSessionId(), "STOPPED", "provider-stop-retention");
        });
        MeetingMediaProvider media = mock(MeetingMediaProvider.class);
        when(media.capability()).thenReturn(mediaCapability());
        MeetingContentDependencies dependencies = () -> retentionReady.get()
                ? dependencies()
                : new MeetingContentDependencies.Status(
                        false, false, true, false, true, true);
        VideoMeetingRecordingService service = new VideoMeetingRecordingService(
                transactionsAt(fixture.now()), provider, media, dependencies);

        var started = service.requestRecording(
                fixture.meetingId(),
                new VideoMeetingContentDtos.RequestRecordingCommand(fixture.planVersion()),
                "recording-retention-start-0001", "corr-retention-start");
        retentionReady.set(false);
        var stopped = service.stopRecording(
                fixture.meetingId(),
                new VideoMeetingContentDtos.StopRecordingCommand(
                        started.response().recordingSession().version()),
                "recording-retention-stop-0001", "corr-retention-stop");

        assertThat(stopped.response().commandState()).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("""
                SELECT stop_consent_snapshot_sha256 ~ '^[0-9a-f]{64}$'
                  FROM vm_meeting_recording_sessions
                 WHERE recording_session_id = ?
                """, Boolean.class,
                started.response().recordingSession().recordingSessionId())).isTrue();
        var blocked = service.requestRecording(
                fixture.meetingId(),
                new VideoMeetingContentDtos.RequestRecordingCommand(fixture.planVersion()),
                "recording-retention-block-0001", "corr-retention-block");
        assertThat(blocked.accepted()).isFalse();
        assertThat(blocked.response().blockers().stream()
                .map(VideoMeetingContentDtos.BlockerResponse::code).toList())
                .contains("EGRESS", "STORAGE");
        verify(provider).start(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void providerOrRegionSwitchCannotStopOrReclaimTheCapturedSession() {
        MeetingRecordingProvider.Capability original = recordingCapability();
        MeetingRecordingProvider.Capability switched = new MeetingRecordingProvider.Capability(
                true, true, true, true, true, true,
                "us-east-1", "GOVERNED_EGRESS");
        String key = "recording-region-recovery-0001";
        String hash = requestHash(fixture.meetingId(), fixture.planVersion());
        MeetingRecordingCommandTransactions oldWorker = transactionsAt(fixture.now());
        Preparation prepared = oldWorker.prepareStart(
                fixture.subject(), fixture.meetingId(), fixture.planVersion(), key, hash,
                "corr-region-original", dependencies(), mediaCapability(), original);

        assertThatThrownBy(() -> transactionsAt(fixture.now().plusMinutes(3)).prepareStart(
                fixture.subject(), fixture.meetingId(), fixture.planVersion(), key, hash,
                "corr-region-switched", dependencies(), mediaCapability(), switched))
                .isInstanceOf(BaseException.class)
                .hasMessage("The governed recording provider is not ready for command recovery.");
        assertThat(jdbc.queryForObject("""
                SELECT recording_provider_code || ':' || recording_processing_region
                  FROM vm_meeting_recording_sessions
                 WHERE recording_session_id = ?
                """, String.class, prepared.session().recordingSessionId()))
                .isEqualTo("GOVERNED_EGRESS:ap-northeast-2");
    }

    @Test
    void stopFailsClosedWhenProviderRegionDiffersFromTheStartSnapshot() {
        MeetingRecordingProvider.Capability original = recordingCapability();
        MeetingRecordingProvider.Capability switched = new MeetingRecordingProvider.Capability(
                true, true, true, true, true, true,
                "us-east-1", "GOVERNED_EGRESS");
        MeetingRecordingCommandTransactions transactions = transactionsAt(fixture.now());
        Preparation prepared = transactions.prepareStart(
                fixture.subject(), fixture.meetingId(), fixture.planVersion(),
                "recording-region-start-0001",
                requestHash(fixture.meetingId(), fixture.planVersion()),
                "corr-region-start", dependencies(), mediaCapability(), original);
        var started = transactions.succeed(
                fixture.subject(), prepared, "provider-start-region-bound");

        Preparation stop = transactions.prepareStop(
                fixture.subject(), fixture.meetingId(),
                started.response().recordingSession().version(),
                "recording-region-stop-0001",
                requestHash(
                        fixture.meetingId(),
                        started.response().recordingSession().version()),
                "corr-region-stop", dependencies(), switched);

        assertThat(stop.execute()).isFalse();
        assertThat(stop.replay().response().blockers().stream()
                .map(VideoMeetingContentDtos.BlockerResponse::code).toList())
                .containsExactly("EGRESS");
        assertThat(jdbc.queryForObject("""
                SELECT recording_state FROM vm_meeting_recording_sessions
                 WHERE recording_session_id = ?
                """, String.class, prepared.session().recordingSessionId()))
                .isEqualTo("RECORDING");
    }

    @Test
    void expiredDurableStartIsReclaimedAndStaleWorkerCannotComplete() {
        String key = "recording-crash-0001";
        String hash = requestHash(fixture.meetingId(), fixture.planVersion());
        MeetingRecordingCommandTransactions oldWorker = transactionsAt(fixture.now());
        Preparation first = oldWorker.prepareStart(
                fixture.subject(), fixture.meetingId(), fixture.planVersion(), key, hash,
                "corr-old-worker", dependencies(), mediaCapability(), recordingCapability());

        assertThat(first.execute()).isTrue();
        JdbcTemplate independent = new JdbcTemplate(dataSource);
        assertThat(independent.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_provider_commands
                 WHERE command_id = ?
                """, String.class, first.command().commandId())).isEqualTo("RUNNING");
        assertThat(independent.queryForObject("""
                SELECT recording_state FROM vm_meeting_recording_sessions
                 WHERE recording_session_id = ?
                """, String.class, first.session().recordingSessionId())).isEqualTo("STARTING");

        MeetingRecordingCommandTransactions recoveredWorker =
                transactionsAt(fixture.now().plusMinutes(3));
        Preparation reclaimed = recoveredWorker.prepareStart(
                fixture.subject(), fixture.meetingId(), fixture.planVersion(), key, hash,
                "corr-recovered-worker", dependencies(), mediaCapability(),
                recordingCapability());

        assertThat(reclaimed.execute()).isTrue();
        assertThat(reclaimed.command().commandId()).isEqualTo(first.command().commandId());
        assertThat(reclaimed.command().recordingSessionId())
                .isEqualTo(first.command().recordingSessionId());
        assertThat(reclaimed.command().attemptCount()).isEqualTo(2);
        assertThat(reclaimed.command().executionFence())
                .isNotEqualTo(first.command().executionFence());
        assertThatThrownBy(() -> oldWorker.succeed(
                fixture.subject(), first, "late-provider-start"))
                .isInstanceOf(BaseException.class);

        var completed = recoveredWorker.succeed(
                fixture.subject(), reclaimed, "provider-start-recovered");

        assertThat(completed.response().commandState()).isEqualTo("RECORDING");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM vm_meeting_recording_provider_commands
                 WHERE command_id = ?
                """, Integer.class, first.command().commandId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_recording_provider_commands
                 WHERE tenant_id = 1 AND meeting_id = ? AND command_type = 'START'
                """, Integer.class, fixture.meetingId())).isOne();
    }

    @Test
    void providerFailureCommitsContentFreeFailureAndSameKeyRecoversOneSession() {
        AtomicInteger attempts = new AtomicInteger();
        MeetingRecordingProvider provider = mock(MeetingRecordingProvider.class);
        when(provider.capability()).thenReturn(recordingCapability());
        when(provider.start(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            MeetingRecordingProvider.Command command = invocation.getArgument(0);
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("private upstream body");
            }
            return new MeetingRecordingProvider.Receipt(
                    command.recordingSessionId(), "STARTED", "provider-retry-001");
        });
        MeetingMediaProvider media = mock(MeetingMediaProvider.class);
        when(media.capability()).thenReturn(mediaCapability());
        VideoMeetingRecordingService service = new VideoMeetingRecordingService(
                transactionsAt(fixture.now()), provider, media, () -> dependencies());
        var request = new VideoMeetingContentDtos.RequestRecordingCommand(
                fixture.planVersion());

        assertThatThrownBy(() -> service.requestRecording(
                fixture.meetingId(), request,
                "recording-provider-retry-0001", "corr-provider-failure"))
                .isInstanceOf(BaseException.class)
                .hasMessage("The governed recording provider is unavailable.")
                .hasNoCause();
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_provider_commands
                 WHERE tenant_id = 1 AND meeting_id = ? AND command_type = 'START'
                """, String.class, fixture.meetingId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT recording_state FROM vm_meeting_recording_sessions
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, String.class, fixture.meetingId())).isEqualTo("FAILED");

        var recovered = service.requestRecording(
                fixture.meetingId(), request,
                "recording-provider-retry-0001", "corr-provider-retry");

        assertThat(recovered.response().commandState()).isEqualTo("RECORDING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_recording_sessions
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Integer.class, fixture.meetingId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM vm_meeting_recording_provider_commands
                 WHERE tenant_id = 1 AND meeting_id = ? AND command_type = 'START'
                """, Integer.class, fixture.meetingId())).isEqualTo(2);
        String audits = String.join("|", jdbc.queryForList("""
                SELECT payload::text FROM sys_audit_outbox
                 WHERE payload ->> 'targetId' = ? ORDER BY created_at
                """, String.class, recovered.response().recordingSession()
                        .recordingSessionId().toString()));
        assertThat(audits).doesNotContain("private upstream body");
    }

    @Test
    void terminalAuditFailureRollsBackSessionAndProviderCommandTogether() {
        MeetingRecordingCommandTransactions transactions = transactionsAt(fixture.now());
        Preparation prepared = transactions.prepareStart(
                fixture.subject(), fixture.meetingId(), fixture.planVersion(),
                "recording-audit-failure-0001",
                requestHash(fixture.meetingId(), fixture.planVersion()),
                "corr-recording-audit-failure", dependencies(), mediaCapability(),
                recordingCapability());
        jdbc.execute("""
                CREATE FUNCTION fail_recording_terminal_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.recording.started' THEN
                        RAISE EXCEPTION 'simulated recording audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_recording_terminal_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_recording_terminal_audit()
                """);

        assertThatThrownBy(() -> transactions.succeed(
                fixture.subject(), prepared, "provider-start-audit-failure"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_provider_commands
                 WHERE command_id = ?
                """, String.class, prepared.command().commandId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT recording_state FROM vm_meeting_recording_sessions
                 WHERE recording_session_id = ?
                """, String.class, prepared.session().recordingSessionId()))
                .isEqualTo("STARTING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.recording.started'
                """, Integer.class)).isZero();
    }

    private MeetingRecordingCommandTransactions transactionsAt(OffsetDateTime now) {
        var target = new MeetingRecordingCommandTransactions(
                meetings, content, new VideoMeetingIntelligenceRepository(jdbc),
                commands, properties, audit,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return (MeetingRecordingCommandTransactions) proxy.getProxy();
    }

    private Fixture fixture() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'LIVE'
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
                   SET recording_policy = 'HOST_OPT_IN'
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
        long planVersion = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_content_plans
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        return new Fixture(
                meetingId, planVersion, now,
                new MeetingRequestContext.Subject(
                        actor, 1, personId, "Meeting host", Set.of("USER"),
                        Set.of("APP.MEETINGS:UPDATE"), Set.of()));
    }

    private MeetingContentDependencies.Status dependencies() {
        return new MeetingContentDependencies.Status(true, true, true, true, true, true);
    }

    private MeetingMediaProvider.Capability mediaCapability() {
        return new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300);
    }

    private MeetingRecordingProvider.Capability recordingCapability() {
        return new MeetingRecordingProvider.Capability(
                true, true, true, true, true, true,
                "ap-northeast-2", "GOVERNED_EGRESS");
    }

    private record Fixture(
            UUID meetingId,
            long planVersion,
            OffsetDateTime now,
            MeetingRequestContext.Subject subject) {
    }
}
