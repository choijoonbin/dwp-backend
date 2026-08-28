package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingTranscriptArtifactDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingTranscriptArtifactFinalizationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TOKEN = "transcript-producer-" + "t".repeat(32);
    private static final String KEY_ID = "transcript-producer-v1";
    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private ObjectMapper mapper;
    private VideoMeetingRepository meetings;
    private VideoMeetingContentRepository content;
    private VideoMeetingIntelligenceRepository intelligence;
    private MeetingTranscriptArtifactRepository artifacts;
    private MeetingTranscriptArtifactFinalizationService service;
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
        mapper = new ObjectMapper().findAndRegisterModules();
        meetings = new VideoMeetingRepository(jdbc, mapper);
        content = new VideoMeetingContentRepository(jdbc);
        intelligence = new VideoMeetingIntelligenceRepository(jdbc);
        artifacts = new MeetingTranscriptArtifactRepository(jdbc);
        audit = new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-meeting-server", "artifact-test", "test"));
        fixture = fixture();
        var verifier = new MeetingTranscriptFinalizationAssertionVerifier(
                TOKEN, KEY_ID, Base64.getEncoder().encodeToString(SECRET), mapper,
                Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC));
        service = transactional(new MeetingTranscriptArtifactFinalizationService(
                meetings, content, intelligence, artifacts, verifier, audit,
                Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC)));
        MeetingRequestContext.set(fixture.subject());
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void trustedFinalizationBindsNoticeConsentHashRegionAndMakesRunReachable() {
        String assertion = assertion(UUID.randomUUID(), fixture.command());

        var response = service.finalizeTranscript(
                fixture.meetingId(), fixture.command(), "artifact-finalize-0001",
                "corr-finalize", TOKEN, assertion);

        assertThat(response.state()).isEqualTo("AVAILABLE");
        assertThat(response.processingRegion()).isEqualTo("ap-northeast-2");
        assertThat(jdbc.queryForObject("""
                SELECT server_side_processing_allowed
                  FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Boolean.class, fixture.artifactId())).isTrue();
        String auditPayload = jdbc.queryForObject("""
                SELECT payload::text FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.transcript-artifact.finalized'
                """, String.class);
        assertThat(auditPayload).doesNotContain("objectKey", "opaque/transcript/source");

        MeetingIntelligenceRunTransactions runs = transactionalRuns();
        var prepared = runs.prepare(
                fixture.subject(), fixture.meetingId(),
                new VideoMeetingIntelligenceDtos.CreateRunCommand(
                        fixture.artifactId(), "ko-KR", fixture.planVersion()),
                "intelligence-after-finalization", "a".repeat(64),
                UUID.randomUUID(), fixture.now().plusSeconds(1));
        assertThat(prepared.execute()).isTrue();
        assertThat(prepared.source().serverSideProcessingAllowed()).isTrue();
    }

    @Test
    void replayJtiIsDeniedWhileNewJtiCanReplaySameIdempotentCommand() {
        String assertion = assertion(UUID.randomUUID(), fixture.command());
        var first = service.finalizeTranscript(
                fixture.meetingId(), fixture.command(), "artifact-finalize-0002",
                "corr-first", TOKEN, assertion);

        assertThatThrownBy(() -> service.finalizeTranscript(
                fixture.meetingId(), fixture.command(), "artifact-finalize-0002",
                "corr-replay", TOKEN, assertion))
                .isInstanceOf(BaseException.class);
        var replay = service.finalizeTranscript(
                fixture.meetingId(), fixture.command(), "artifact-finalize-0002",
                "corr-new-assertion", TOKEN,
                assertion(UUID.randomUUID(), fixture.command()));

        assertThat(replay.artifactId()).isEqualTo(first.artifactId());
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.transcript-artifact.finalized'
                """, Integer.class)).isOne();
    }

    @Test
    void invalidProducerCredentialOrConsentCannotMutateArtifact() {
        assertThatThrownBy(() -> service.finalizeTranscript(
                fixture.meetingId(), fixture.command(), "artifact-finalize-0003",
                "corr-invalid", "wrong-token",
                assertion(UUID.randomUUID(), fixture.command())))
                .isInstanceOf(BaseException.class);
        var invalidConsent = new MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand(
                fixture.artifactId(), fixture.command().expectedArtifactVersion(),
                fixture.planVersion(), fixture.noticeId(), "b".repeat(64),
                fixture.command().sourceSha256(), fixture.command().processingRegion(),
                fixture.command().storageProvider(), fixture.command().objectKey(),
                fixture.command().contentType(), fixture.command().sizeBytes());
        assertThatThrownBy(() -> service.finalizeTranscript(
                fixture.meetingId(), invalidConsent, "artifact-finalize-0004",
                "corr-consent", TOKEN, assertion(UUID.randomUUID(), invalidConsent)))
                .isInstanceOf(BaseException.class);

        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_transcript_finalization_assertion_replay
                """, Integer.class)).isZero();
    }

    private MeetingIntelligenceRunTransactions transactionalRuns() {
        MeetingContentDependencies dependencies = MeetingContentDependencies::failClosedStatus;
        var target = new MeetingIntelligenceRunTransactions(
                meetings, content, intelligence, new MeetingContentAccessPolicy(),
                dependencies, null, null, null, audit);
        return transactional(target);
    }

    private Fixture fixture() {
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
        long artifactVersion = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Long.class, artifactId);
        long planVersion = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_content_plans
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        String consentHash = intelligence.consentEvidence(1, meetingId, noticeId)
                .snapshotSha256();
        var command = new MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand(
                artifactId, artifactVersion, planVersion, noticeId, consentHash,
                "a".repeat(64), "ap-northeast-2", "BROKER",
                "opaque/transcript/source", "application/json", 1_024);
        var subject = new MeetingRequestContext.Subject(
                actor, 1, personId, "Artifact producer", Set.of("SERVICE"),
                Set.of("APP.MEETINGS:UPDATE"), Set.of());
        return new Fixture(
                meetingId, artifactId, noticeId, planVersion, now, subject, command);
    }

    private String assertion(
            UUID jti,
            MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand command) {
        try {
            String bodySha256 = VideoMeetingCommandPolicy.requestHash(
                    fixture.meetingId(), command.artifactId(), command.expectedArtifactVersion(),
                    command.expectedContentPlanVersion(), command.contentNoticeId(),
                    command.consentSnapshotSha256(), command.sourceSha256(),
                    command.processingRegion(), command.storageProvider(), command.objectKey(),
                    command.contentType(), command.sizeBytes());
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("v", 1);
            payload.put("kid", KEY_ID);
            payload.put("method", "POST");
            payload.put("path", "/internal/v1/meetings/" + fixture.meetingId()
                    + "/artifacts/transcript/finalize");
            payload.put("tenantId", 1);
            payload.put("meetingId", fixture.meetingId());
            payload.put("artifactId", command.artifactId());
            payload.put("iat", fixture.now().toEpochSecond());
            payload.put("exp", fixture.now().plusSeconds(30).toEpochSecond());
            payload.put("jti", jti);
            payload.put("bodySha256", bodySha256);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(payload));
            String input = "dwpaf1." + encoded;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return input + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
            UUID noticeId,
            long planVersion,
            OffsetDateTime now,
            MeetingRequestContext.Subject subject,
            MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand command) {
    }
}
