package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordingAccessDtos;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordingArtifactDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class MeetingRecordingArtifactFinalizationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TOKEN = "recording-producer-" + "t".repeat(32);
    private static final String KEY_ID = "recording-producer-v1";
    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private ObjectMapper mapper;
    private VideoMeetingRepository meetings;
    private VideoMeetingContentRepository content;
    private VideoMeetingIntelligenceRepository intelligence;
    private MeetingRecordingArtifactRepository artifacts;
    private VideoMeetingAuditRecorder audit;
    private MeetingRecordingArtifactFinalizationService finalization;
    private MeetingRecordingProvider recordingProvider;
    private Fixture fixture;

    @BeforeEach
    void setup() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        meetings = new VideoMeetingRepository(jdbc, mapper);
        content = new VideoMeetingContentRepository(jdbc);
        intelligence = new VideoMeetingIntelligenceRepository(jdbc);
        artifacts = new MeetingRecordingArtifactRepository(jdbc);
        audit = new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-meeting-server", "recording-artifact-test", "test"));
        fixture = fixture();
        MeetingRecordingDeletionProperties deletionProperties = deletionProperties();
        jdbc.update("""
                UPDATE vm_meeting_recording_deletion_health
                   SET last_success_at = ?, last_attempt_at = ?, updated_at = ?,
                       last_provider_code = 'GOVERNED_EGRESS'
                 WHERE health_key = 'RECORDING_RETENTION'
                """, fixture.now(), fixture.now(), fixture.now());
        MeetingRecordingDeletionReadiness deletionReadiness =
                new MeetingRecordingDeletionReadiness(
                        new MeetingRecordingDeletionRepository(jdbc),
                        deletionProperties,
                        Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC));
        var verifier = new MeetingRecordingArtifactAssertionVerifier(
                TOKEN, KEY_ID, Base64.getEncoder().encodeToString(SECRET), mapper,
                Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC));
        recordingProvider = mock(MeetingRecordingProvider.class);
        when(recordingProvider.capability()).thenReturn(
                new MeetingRecordingProvider.Capability(
                        true, true, true, true, true, true,
                        true, 300, true,
                        "ap-northeast-2", "GOVERNED_EGRESS"));
        finalization = transactional(new MeetingRecordingArtifactFinalizationService(
                meetings, content, artifacts, verifier, audit, deletionReadiness,
                recordingProvider, transactionManager,
                Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC)));
        MeetingRequestContext.set(fixture.producer());
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void stoppedSessionFinalizesAndAViewOnlyParticipantGetsAShortLivedOpaqueTicket() {
        MeetingRecordingArtifactDtos.FinalizeRecordingCommand command = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));

        var finalized = finalize(command, "recording-finalize-0001", UUID.randomUUID());

        assertThat(finalized.state()).isEqualTo("AVAILABLE");
        assertThat(finalized.recordingSessionId()).isEqualTo(fixture.sessionId());
        assertThat(finalized.version()).isEqualTo(fixture.artifactVersion() + 1);
        assertThat(jdbc.queryForObject("""
                SELECT recording_provider_code FROM vm_meeting_artifacts
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("GOVERNED_EGRESS");
        String finalizationAudit = auditPayload("meeting.recording-artifact.finalized");
        assertThat(finalizationAudit).doesNotContain(
                command.objectKey(), command.sourceSha256(),
                command.consentSnapshotSha256(), "accessUrl", TOKEN);

        MeetingRecordingHttpProperties properties = accessProperties();
        CapturingAccessProvider provider = new CapturingAccessProvider(fixture.now());
        MeetingRecordingAccessTransactions accessTransactions = transactional(
                new MeetingRecordingAccessTransactions(
                        meetings, new MeetingRecordingAccessRepository(jdbc), audit,
                        properties, Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC)));
        MeetingRecordingAccessService access = new MeetingRecordingAccessService(
                provider, accessTransactions);
        MeetingRequestContext.set(fixture.viewer());

        MeetingRecordingAccessDtos.AccessTicketResponse ticket = access.issueAccessTicket(
                fixture.meetingId(), fixture.artifactId(),
                new MeetingRecordingAccessDtos.AccessTicketCommand(finalized.version()),
                command.objectKey());

        assertThat(ticket.artifactVersion()).isEqualTo(finalized.version());
        assertThat(ticket.accessUrl()).startsWith(
                "https://media.example.test/playback/opaque-ticket");
        assertThat(provider.request.objectKey()).isEqualTo(command.objectKey());
        assertThat(provider.request.sourceSha256()).isEqualTo(command.sourceSha256());
        String accessAudit = jdbc.queryForObject("""
                SELECT string_agg(payload::text, '') FROM sys_audit_outbox
                 WHERE payload ->> 'action' IN (
                    'meeting.recording.access-requested',
                    'meeting.recording.access-issued')
                """, String.class);
        assertThat(accessAudit).doesNotContain(
                command.objectKey(), command.sourceSha256(), ticket.accessUrl());
    }

    @Test
    void liveProviderProcessingRegionChangeBlocksFinalization() {
        when(recordingProvider.capability()).thenReturn(
                new MeetingRecordingProvider.Capability(
                        true, true, true, true, true, true,
                        true, 300, true,
                        "us-east-1", "GOVERNED_EGRESS"));
        MeetingRecordingArtifactDtos.FinalizeRecordingCommand command = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));

        assertThatThrownBy(() -> finalize(
                command, "recording-finalize-region-change", UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .hasMessage("Recording provider governance changed before finalization.");
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isNotEqualTo("AVAILABLE");
    }

    @Test
    void accessAuditFailuresPreventProviderInvocationOrTicketReturn() {
        var finalized = finalize(command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20)), "recording-finalize-access-audit",
                UUID.randomUUID());
        MeetingRecordingHttpProperties properties = accessProperties();
        CapturingAccessProvider provider = new CapturingAccessProvider(fixture.now());
        MeetingRecordingAccessTransactions transactions = transactional(
                new MeetingRecordingAccessTransactions(
                        meetings, new MeetingRecordingAccessRepository(jdbc), audit,
                        properties, Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC)));
        MeetingRecordingAccessService access = new MeetingRecordingAccessService(
                provider, transactions);
        MeetingRequestContext.set(fixture.viewer());
        jdbc.execute("""
                CREATE FUNCTION fail_recording_access_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.recording.access-requested' THEN
                        RAISE EXCEPTION 'simulated access request audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_recording_access_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_recording_access_audit()
                """);

        assertThatThrownBy(() -> access.issueAccessTicket(
                fixture.meetingId(), fixture.artifactId(),
                new MeetingRecordingAccessDtos.AccessTicketCommand(finalized.version()),
                "corr-access-audit-request"))
                .isInstanceOf(RuntimeException.class);
        assertThat(provider.request).isNull();

        jdbc.execute("DROP TRIGGER fail_recording_access_audit_trigger ON sys_audit_outbox");
        jdbc.execute("DROP FUNCTION fail_recording_access_audit()");
        jdbc.execute("""
                CREATE FUNCTION fail_recording_access_issued_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.recording.access-issued' THEN
                        RAISE EXCEPTION 'simulated access issued audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_recording_access_issued_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_recording_access_issued_audit()
                """);

        assertThatThrownBy(() -> access.issueAccessTicket(
                fixture.meetingId(), fixture.artifactId(),
                new MeetingRecordingAccessDtos.AccessTicketCommand(finalized.version()),
                "corr-access-audit-issued"))
                .isInstanceOf(RuntimeException.class);
        assertThat(provider.request).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.recording.access-requested'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.recording.access-issued'
                """, Integer.class)).isZero();
    }

    @Test
    void immutableStoppedSessionAllowsLateCallbackAfterTheCurrentPlanChanges() {
        UUID replacementNotice = UUID.randomUUID();
        jdbc.update("""
                UPDATE vm_meeting_content_notices
                   SET notice_state = 'SUPERSEDED', superseded_at = ?
                 WHERE notice_id = ?
                """, fixture.now(), fixture.noticeId());
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, 2, FALSE, TRUE, FALSE, ?)
                """, replacementNotice, fixture.meetingId(), fixture.actorUserId());
        jdbc.update("""
                UPDATE vm_meeting_content_plans
                   SET recording_requested = FALSE, transcription_requested = TRUE,
                       plan_state = 'READY', current_notice_id = ?, notice_revision = 2,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, replacementNotice, fixture.now(), fixture.meetingId());
        MeetingRecordingArtifactDtos.FinalizeRecordingCommand command = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));

        var response = finalize(command, "recording-finalize-late-0001", UUID.randomUUID());

        assertThat(response.state()).isEqualTo("AVAILABLE");
        assertThat(response.recordingSessionId()).isEqualTo(fixture.sessionId());
    }

    @Test
    void stoppedConsentSnapshotCannotBeRewrittenByLaterParticipantEvidence() {
        jdbc.update("""
                DELETE FROM vm_meeting_content_notice_acknowledgements
                 WHERE tenant_id = 1 AND meeting_id = ? AND notice_id = ?
                """, fixture.meetingId(), fixture.noticeId());
        String mutated = intelligence.consentEvidence(
                1, fixture.meetingId(), fixture.noticeId()).snapshotSha256();
        assertThat(mutated).isNotEqualTo(fixture.consentSha256());
        var rewritten = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), mutated,
                fixture.now().plusDays(20));
        assertThatThrownBy(() -> finalize(
                rewritten, "recording-finalize-rewritten-consent", UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .hasMessage("Participant consent evidence is incomplete or does not match.");

        var original = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));
        assertThat(finalize(
                original, "recording-finalize-stopped-consent", UUID.randomUUID()).state())
                .isEqualTo("AVAILABLE");
    }

    @Test
    void retainedLocatorCannotBeOverwrittenByASecondFinalization() {
        jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'UNAVAILABLE', storage_provider = 'BROKER',
                       object_key = 'legacy/restricted-object', content_type = 'video/mp4',
                       size_bytes = 1024, sha256 = ?, retention_until = ?
                 WHERE artifact_id = ?
                """, "e".repeat(64), fixture.now().plusDays(1), fixture.artifactId());
        var request = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));

        assertThatThrownBy(() -> finalize(
                request, "recording-finalize-overwrite", UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .hasMessage("A retained recording locator cannot be overwritten.");

        assertThat(jdbc.queryForObject("""
                SELECT object_key FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId()))
                .isEqualTo("legacy/restricted-object");
        assertThat(assertionCount()).isZero();
    }

    @Test
    void finalizationAuditFailureRollsBackArtifactReplayAndAuditTogether() {
        jdbc.execute("""
                CREATE FUNCTION fail_recording_finalize_audit() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.payload ->> 'action' = 'meeting.recording-artifact.finalized' THEN
                        RAISE EXCEPTION 'simulated finalization audit outage';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_recording_finalize_audit_trigger
                BEFORE INSERT ON sys_audit_outbox
                FOR EACH ROW EXECUTE FUNCTION fail_recording_finalize_audit()
                """);
        var request = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));

        assertThatThrownBy(() -> finalize(
                request, "recording-finalize-audit-failure", UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("""
                SELECT recording_finalization_idempotency_key IS NULL
                  FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Boolean.class, fixture.artifactId())).isTrue();
        assertThat(assertionCount()).isZero();
        assertThat(finalizationAuditCount()).isZero();
    }

    @Test
    void crossTenantAssertionCannotReachOrMutateAnotherTenantArtifact() {
        var otherTenant = new MeetingRequestContext.Subject(
                fixture.actorUserId(), 2, UUID.randomUUID(), "Other producer",
                Set.of("SERVICE"), Set.of("APP.MEETINGS:UPDATE"), Set.of());
        MeetingRequestContext.set(otherTenant);
        var request = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));

        assertThatThrownBy(() -> finalization.finalizeRecording(
                fixture.meetingId(), request, "recording-finalize-cross-tenant",
                "corr-cross-tenant", TOKEN,
                assertion(request, UUID.randomUUID(), 2)))
                .isInstanceOf(BaseException.class);

        assertThat(jdbc.queryForObject("""
                SELECT recording_finalization_idempotency_key IS NULL
                  FROM vm_meeting_artifacts WHERE artifact_id = ? AND tenant_id = 1
                """, Boolean.class, fixture.artifactId())).isTrue();
        assertThat(assertionCount()).isZero();
    }

    @Test
    void staleWrongSessionReplayAndExpiredStopRetentionFailClosedAtomically() {
        MeetingRecordingArtifactDtos.FinalizeRecordingCommand stale = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion() + 1,
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));
        assertThatThrownBy(() -> finalize(
                stale, "recording-finalize-stale-0001", UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
        MeetingRecordingArtifactDtos.FinalizeRecordingCommand wrongSession = command(
                fixture.artifactId(), UUID.randomUUID(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));
        assertThatThrownBy(() -> finalize(
                wrongSession, "recording-finalize-session-0001", UUID.randomUUID()))
                .isInstanceOf(BaseException.class);
        assertThat(assertionCount()).isZero();
        assertThat(finalizationAuditCount()).isZero();

        MeetingRecordingArtifactDtos.FinalizeRecordingCommand valid = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));
        UUID jti = UUID.randomUUID();
        var first = finalize(valid, "recording-finalize-replay-0001", jti);
        assertThatThrownBy(() -> finalize(valid, "recording-finalize-replay-0001", jti))
                .isInstanceOf(BaseException.class);
        var replay = finalize(
                valid, "recording-finalize-replay-0001", UUID.randomUUID());
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(finalizationAuditCount()).isOne();

        Fixture expired = newSession(
                fixture.now().minusDays(31), 30, fixture.consentSha256());
        MeetingRecordingArtifactDtos.FinalizeRecordingCommand delayed = command(
                UUID.randomUUID(), expired.sessionId(), 0,
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(10));
        assertThatThrownBy(() -> finalize(
                delayed, "recording-finalize-expired-0001", UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .hasMessage("The recording artifact retention window has expired.");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Integer.class, delayed.artifactId())).isZero();
    }

    @Test
    void sequentialStoppedSessionsReceiveDistinctArtifactsAndAdminRoleCannotBypassParticipation() {
        var firstCommand = command(
                fixture.artifactId(), fixture.sessionId(), fixture.artifactVersion(),
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));
        var first = finalize(firstCommand, "recording-finalize-sequential-0001",
                UUID.randomUUID());
        Fixture secondSession = newSession(
                fixture.now().minusMinutes(1), 30, fixture.consentSha256());
        UUID secondArtifactId = UUID.randomUUID();
        var secondCommand = command(
                secondArtifactId, secondSession.sessionId(), 0,
                fixture.planVersion(), fixture.noticeId(), fixture.consentSha256(),
                fixture.now().plusDays(20));
        var second = finalize(secondCommand, "recording-finalize-sequential-0002",
                UUID.randomUUID());

        assertThat(second.artifactId()).isNotEqualTo(first.artifactId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ?
                   AND artifact_type = 'RECORDING' AND artifact_state = 'AVAILABLE'
                """, Integer.class, fixture.meetingId())).isEqualTo(2);

        MeetingRecordingHttpProperties properties = accessProperties();
        MeetingRecordingAccessTransactions transactions = transactional(
                new MeetingRecordingAccessTransactions(
                        meetings, new MeetingRecordingAccessRepository(jdbc), audit,
                        properties, Clock.fixed(fixture.now().toInstant(), ZoneOffset.UTC)));
        MeetingRequestContext.set(new MeetingRequestContext.Subject(
                999_999, 1, UUID.randomUUID(), "Tenant admin", Set.of("TENANT_ADMIN"),
                Set.of("ADMIN.MEETINGS:MANAGE"), Set.of()));
        MeetingRecordingAccessService access = new MeetingRecordingAccessService(
                new CapturingAccessProvider(fixture.now()), transactions);

        assertThatThrownBy(() -> access.issueAccessTicket(
                fixture.meetingId(), first.artifactId(),
                new MeetingRecordingAccessDtos.AccessTicketCommand(first.version()), null))
                .isInstanceOf(BaseException.class)
                .hasMessage("The recording artifact was not found.");
    }

    @Test
    void legacyAvailableRecordingIsQuarantinedWithoutInventedProvenance() {
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("22")).load().migrate();
        JdbcTemplate legacy = new JdbcTemplate(dataSource);
        UUID artifactId = legacy.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE artifact_type = 'RECORDING' LIMIT 1
                """, UUID.class);
        legacy.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = 'BROKER',
                       object_key = 'legacy/opaque-object', content_type = 'video/mp4',
                       size_bytes = 1024, sha256 = ?, retention_until = ?
                 WHERE artifact_id = ?
                """, "a".repeat(64), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                artifactId);

        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();

        assertThat(legacy.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, artifactId)).isEqualTo("UNAVAILABLE");
        assertThat(legacy.queryForObject("""
                SELECT object_key FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, artifactId)).isEqualTo("legacy/opaque-object");
        assertThat(legacy.queryForObject("""
                SELECT metadata ->> 'reason' FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, artifactId))
                .isEqualTo("LEGACY_RECORDING_PROVENANCE_MISSING");
        assertThatThrownBy(() -> legacy.update("""
                UPDATE vm_meeting_artifacts SET artifact_state = 'AVAILABLE'
                 WHERE artifact_id = ?
                """, artifactId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyDeletedLocatorIsQuarantinedThenClearedOnlyWithDeletionEvidence() {
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("22")).load().migrate();
        JdbcTemplate legacy = new JdbcTemplate(dataSource);
        UUID artifactId = legacy.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE artifact_type = 'RECORDING' LIMIT 1
                """, UUID.class);
        UUID meetingId = legacy.queryForObject("""
                SELECT meeting_id FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, UUID.class, artifactId);
        legacy.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'DELETED', storage_provider = 'BROKER',
                       object_key = 'legacy/deleted-opaque-object',
                       content_type = 'video/mp4', size_bytes = 1024, sha256 = NULL,
                       retention_until = NULL
                 WHERE artifact_id = ?
                """, artifactId);

        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();

        assertThat(legacy.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, artifactId)).isEqualTo("UNAVAILABLE");
        assertThat(legacy.queryForObject("""
                SELECT metadata ->> 'reason' FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, artifactId))
                .isEqualTo("LEGACY_RECORDING_DELETION_PENDING");
        OffsetDateTime cleanupAt = OffsetDateTime.now(ZoneOffset.UTC);
        MeetingRecordingDeletionProperties properties = deletionProperties();
        MeetingRecordingDeletionRepository repository =
                new MeetingRecordingDeletionRepository(legacy);
        MeetingRecordingDeletionTransactions deletion = transactional(
                new MeetingRecordingDeletionTransactions(
                        repository, properties, audit,
                        Clock.fixed(cleanupAt.toInstant(), ZoneOffset.UTC)));
        var cycle = deletion.claimCycle();
        cycle = deletion.renewCycle(cycle);
        var prepared = deletion.prepareNext(cycle, "GOVERNED_EGRESS", true);
        deletion.succeed(prepared, new MeetingRecordingProvider.DeletionReceipt(
                artifactId, prepared.artifact().version(),
                "provider-legacy-cleanup", cleanupAt));
        deletion.completeCycle(cycle, "GOVERNED_EGRESS");

        assertThat(legacy.queryForObject("""
                SELECT artifact_state = 'DELETED'
                       AND object_key IS NULL AND storage_provider IS NULL
                       AND recording_deletion_command_id IS NOT NULL
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_id = ?
                """, Boolean.class, meetingId, artifactId)).isTrue();
    }

    private void migrate() {
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
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
                VALUES (?, 1, ?, 1, TRUE, FALSE, FALSE, ?)
                """, noticeId, meetingId, actor);
        jdbc.update("""
                UPDATE vm_meeting_content_plans
                   SET recording_requested = TRUE, transcription_requested = FALSE,
                       ai_summary_requested = FALSE, e2ee_enabled = FALSE,
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
        long planVersion = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_content_plans
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'RECORDING'
                """, UUID.class, meetingId);
        long artifactVersion = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Long.class, artifactId);
        Fixture base = new Fixture(
                meetingId, artifactId, null, noticeId, planVersion, artifactVersion,
                now, actor, personId, null, null, null);
        fixture = base;
        String consentSha = intelligence.consentEvidence(1, meetingId, noticeId)
                .snapshotSha256();
        Fixture session = newSession(now.minusMinutes(1), 30, consentSha);
        var producer = new MeetingRequestContext.Subject(
                actor, 1, personId, "Recording producer", Set.of("SERVICE"),
                Set.of("APP.MEETINGS:UPDATE"), Set.of());
        var viewer = new MeetingRequestContext.Subject(
                actor, 1, personId, "Meeting viewer", Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of());
        return new Fixture(
                meetingId, artifactId, session.sessionId(), noticeId, planVersion,
                artifactVersion, now, actor, personId, consentSha, producer, viewer);
    }

    private Fixture newSession(
            OffsetDateTime stoppedAt, int retentionDays, String consentSha256) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_recording_sessions (
                    recording_session_id, tenant_id, meeting_id, plan_version,
                    notice_id, recording_state, requested_at, requested_by,
                    stop_requested_at, stop_requested_by, started_at, stopped_at,
                    artifact_retention_days, recording_provider_code,
                    recording_processing_region, stop_consent_snapshot_sha256,
                    updated_at)
                VALUES (?, 1, ?, ?, ?, 'STOPPED', ?, ?, ?, ?, ?, ?, ?,
                        'GOVERNED_EGRESS', 'ap-northeast-2', ?, ?)
                """, sessionId, fixture.meetingId(), fixture.planVersion(),
                fixture.noticeId(), stoppedAt.minusMinutes(10), fixture.actorUserId(),
                stoppedAt.minusMinutes(1), fixture.actorUserId(),
                stoppedAt.minusMinutes(9), stoppedAt, retentionDays,
                consentSha256, stoppedAt);
        jdbc.update("""
                INSERT INTO vm_meeting_recording_provider_commands (
                    command_id, tenant_id, meeting_id, recording_session_id,
                    command_type, command_state, actor_user_id, idempotency_key,
                    request_sha256, correlation_id, attempt_count, provider_code,
                    provider_command_id, requested_at, completed_at)
                VALUES (?, 1, ?, ?, 'STOP', 'SUCCEEDED', ?, ?, ?, ?, 1,
                        'GOVERNED_EGRESS', ?, ?, ?)
                """, UUID.randomUUID(), fixture.meetingId(), sessionId,
                fixture.actorUserId(), "recording-stop-" + sessionId,
                "d".repeat(64), "corr-stop-" + sessionId,
                "provider-stop-" + sessionId, stoppedAt.minusMinutes(1), stoppedAt);
        return new Fixture(
                fixture.meetingId(), fixture.artifactId(), sessionId,
                fixture.noticeId(), fixture.planVersion(), fixture.artifactVersion(),
                fixture.now(), fixture.actorUserId(), fixture.personId(),
                fixture.consentSha256(), fixture.producer(), fixture.viewer());
    }

    private MeetingRecordingArtifactDtos.FinalizeRecordingCommand command(
            UUID artifactId,
            UUID sessionId,
            long artifactVersion,
            long planVersion,
            UUID noticeId,
            String consentSha,
            OffsetDateTime retentionUntil) {
        return new MeetingRecordingArtifactDtos.FinalizeRecordingCommand(
                artifactId, sessionId, artifactVersion, planVersion, noticeId,
                consentSha, "a".repeat(64), "ap-northeast-2", "BROKER",
                "tenant-1/recordings/opaque-" + artifactId,
                "video/mp4", 1_024, retentionUntil);
    }

    private MeetingRecordingArtifactDtos.RecordingArtifactResponse finalize(
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand command,
            String idempotencyKey,
            UUID jti) {
        return finalization.finalizeRecording(
                fixture.meetingId(), command, idempotencyKey, command.objectKey(),
                TOKEN, assertion(command, jti));
    }

    private String assertion(
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand command, UUID jti) {
        return assertion(command, jti, 1);
    }

    private String assertion(
            MeetingRecordingArtifactDtos.FinalizeRecordingCommand command,
            UUID jti,
            long tenantId) {
        try {
            String bodySha = VideoMeetingCommandPolicy.requestHash(
                    fixture.meetingId(), command.artifactId(), command.recordingSessionId(),
                    command.expectedArtifactVersion(), command.expectedContentPlanVersion(),
                    command.contentNoticeId(), command.consentSnapshotSha256(),
                    command.sourceSha256(), command.processingRegion(),
                    command.storageProvider(), command.objectKey(), command.contentType(),
                    command.sizeBytes(), command.retentionUntil());
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("v", 1);
            payload.put("kid", KEY_ID);
            payload.put("method", "POST");
            payload.put("path", "/internal/v1/meetings/" + fixture.meetingId()
                    + "/artifacts/recording/finalize");
            payload.put("tenantId", tenantId);
            payload.put("meetingId", fixture.meetingId());
            payload.put("recordingSessionId", command.recordingSessionId());
            payload.put("artifactId", command.artifactId());
            payload.put("iat", fixture.now().toEpochSecond());
            payload.put("exp", fixture.now().plusSeconds(30).toEpochSecond());
            payload.put("jti", jti);
            payload.put("bodySha256", bodySha);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(payload));
            String input = "dwpraf1." + encoded;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return input + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MeetingRecordingHttpProperties accessProperties() {
        MeetingRecordingHttpProperties properties = new MeetingRecordingHttpProperties();
        properties.setAccessTicketAllowedHosts(Set.of("media.example.test"));
        properties.setAccessTicketPathPrefix("/playback/");
        properties.setAccessTicketTtl(Duration.ofMinutes(2));
        return properties;
    }

    private MeetingRecordingDeletionProperties deletionProperties() {
        MeetingRecordingDeletionProperties properties =
                new MeetingRecordingDeletionProperties();
        properties.setEnabled(true);
        properties.setPollDelay(Duration.ofMinutes(5));
        properties.setLeaseDuration(Duration.ofMinutes(1));
        properties.setWorkerId("recording-artifact-test");
        return properties;
    }

    private int assertionCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_recording_artifact_assertion_replay
                """, Integer.class);
    }

    private int finalizationAuditCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.recording-artifact.finalized'
                """, Integer.class);
    }

    private String auditPayload(String action) {
        return jdbc.queryForObject("""
                SELECT payload::text FROM sys_audit_outbox
                 WHERE payload ->> 'action' = ?
                """, String.class, action);
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

    private static final class CapturingAccessProvider implements MeetingRecordingProvider {
        private final OffsetDateTime now;
        private AccessRequest request;

        private CapturingAccessProvider(OffsetDateTime now) {
            this.now = now;
        }

        @Override public Capability capability() { return Capability.unavailable(); }
        @Override public Receipt start(Command command) { throw new UnsupportedOperationException(); }
        @Override public Receipt stop(Command command) { throw new UnsupportedOperationException(); }
        @Override public DeletionReceipt delete(DeleteRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccessTicket issueAccessTicket(AccessRequest request) {
            this.request = request;
            return new AccessTicket(
                    request.artifactId(), request.requesterUserId(), request.artifactVersion(),
                    URI.create("https://media.example.test/playback/opaque-ticket"
                            + "?token=opaque-access-token-001"),
                    now.plusMinutes(1));
        }
    }

    private record Fixture(
            UUID meetingId,
            UUID artifactId,
            UUID sessionId,
            UUID noticeId,
            long planVersion,
            long artifactVersion,
            OffsetDateTime now,
            long actorUserId,
            UUID personId,
            String consentSha256,
            MeetingRequestContext.Subject producer,
            MeetingRequestContext.Subject viewer) {
    }
}
