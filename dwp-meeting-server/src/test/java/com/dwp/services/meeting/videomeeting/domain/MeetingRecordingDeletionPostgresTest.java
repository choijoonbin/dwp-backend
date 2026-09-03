package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionCycle;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.PreparedDeletion;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingHttpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class MeetingRecordingDeletionPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private OffsetDateTime now;
    private MeetingRecordingDeletionProperties properties;
    private MeetingRecordingDeletionRepository repository;
    private MeetingRecordingDeletionReadiness deletionReadiness;
    private VideoMeetingAuditRecorder audit;
    private Fixture fixture;

    @BeforeEach
    void setup() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        now = OffsetDateTime.now(ZoneOffset.UTC);
        properties = properties();
        repository = new MeetingRecordingDeletionRepository(jdbc);
        deletionReadiness = new MeetingRecordingDeletionReadiness(
                repository, properties,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        audit = new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-meeting-server", "recording-deletion-test", "test"));
        fixture = fixture();
    }

    @Test
    void successClearsLocatorCommitsEvidenceAndAuditAndMakesWorkerReady() {
        CapturingDeletionProvider provider = new CapturingDeletionProvider(now);
        MeetingRecordingDeletionService service = service(provider, transactionsAt(now, audit));
        VideoMeetingRepository meetings = new VideoMeetingRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThat(meetings.detail(meetings.lockMeeting(1, fixture.meetingId()))
                .artifacts()).noneMatch(artifact ->
                        artifact.artifactId().equals(fixture.artifactId()));
        assertThat(service.purgeExpired()).isOne();

        assertThat(provider.deleteCount).isOne();
        assertThat(provider.transactionObserved).isFalse();
        assertThat(provider.commandVisibleBeforeDelete).isTrue();
        assertThat(provider.lastRequest.objectKey()).isEqualTo(fixture.objectKey());
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT object_key IS NULL AND storage_provider IS NULL
                  FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Boolean.class, fixture.artifactId())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("SUCCEEDED");
        String auditPayload = jdbc.queryForObject("""
                SELECT payload::text FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.recording-deletion.completed'
                """, String.class);
        assertThat(auditPayload).doesNotContain(
                fixture.objectKey(), fixture.sourceSha256(), "storageProvider");
        assertThat(readiness(now).ready()).isTrue();
        assertThat(service.purgeExpired()).isZero();
        assertThat(provider.deleteCount).isOne();
    }

    @Test
    void providerFailureRetainsLocatorAndNextCycleReclaimsTheSameCommand() {
        CapturingDeletionProvider provider = new CapturingDeletionProvider(now);
        provider.fail = true;
        MeetingRecordingDeletionService service = service(provider, transactionsAt(now, audit));

        assertThat(service.purgeExpired()).isZero();

        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT object_key FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo(fixture.objectKey());
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("FAILED");
        assertThat(readiness(now).ready()).isFalse();

        provider.fail = false;
        assertThat(service.purgeExpired()).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM vm_meeting_recording_deletion_commands
                 WHERE artifact_id = ?
                """, Integer.class, fixture.artifactId())).isEqualTo(2);
        assertThat(provider.deleteCount).isEqualTo(2);
    }

    @Test
    void expiredCycleIsReclaimedAndTheOldWorkerCannotDeleteOrTerminate() {
        MeetingRecordingDeletionTransactions oldWorker = transactionsAt(now, audit);
        DeletionCycle oldCycle = oldWorker.claimCycle();
        PreparedDeletion oldPrepared = oldWorker.prepareNext(
                oldCycle, "GOVERNED_EGRESS", true);
        OffsetDateTime recoveredAt = now.plusMinutes(2);
        MeetingRecordingDeletionTransactions newWorker = transactionsAt(recoveredAt, audit);
        DeletionCycle newCycle = newWorker.claimCycle();
        PreparedDeletion newPrepared = newWorker.prepareNext(
                newCycle, "GOVERNED_EGRESS", true);

        assertThat(newPrepared.command().attemptCount()).isEqualTo(2);
        assertThat(newPrepared.command().commandId())
                .isEqualTo(oldPrepared.command().commandId());
        assertThatThrownBy(() -> oldWorker.succeed(
                oldPrepared, receipt(oldPrepared, now)))
                .isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("AVAILABLE");

        newWorker.succeed(newPrepared, receipt(newPrepared, recoveredAt));
        newWorker.completeCycle(newCycle, "GOVERNED_EGRESS");
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("DELETED");
    }

    @Test
    void crashAfterProviderSuccessReusesTheOriginalReceiptAfterLeaseReclaim() {
        MeetingRecordingDeletionTransactions crashedWorker = transactionsAt(now, audit);
        DeletionCycle crashedCycle = crashedWorker.claimCycle();
        PreparedDeletion crashedPrepared = crashedWorker.prepareNext(
                crashedCycle, "GOVERNED_EGRESS", true);
        MeetingRecordingProvider.DeletionReceipt durableProviderReceipt =
                receipt(crashedPrepared, now);

        OffsetDateTime recoveredAt = now.plusMinutes(2);
        MeetingRecordingDeletionTransactions recoveredWorker =
                transactionsAt(recoveredAt, audit);
        DeletionCycle recoveredCycle = recoveredWorker.claimCycle();
        PreparedDeletion recoveredPrepared = recoveredWorker.prepareNext(
                recoveredCycle, "GOVERNED_EGRESS", true);

        assertThat(recoveredPrepared.command().commandId())
                .isEqualTo(crashedPrepared.command().commandId());
        assertThat(recoveredPrepared.command().attemptCount()).isEqualTo(2);
        assertThat(recoveredPrepared.command().requestedAt())
                .isEqualTo(crashedPrepared.command().requestedAt());

        recoveredWorker.succeed(recoveredPrepared, durableProviderReceipt);
        recoveredWorker.completeCycle(recoveredCycle, "GOVERNED_EGRESS");

        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("SUCCEEDED");
    }

    @Test
    void distributedCycleClaimIsExclusiveAndAuditFailureRollsBackTerminalProjection() {
        VideoMeetingAuditRecorder failingAudit = mock(VideoMeetingAuditRecorder.class);
        doThrow(new IllegalStateException("audit unavailable")).when(failingAudit)
                .recordingDeletion(
                        anyLong(), any(UUID.class), any(UUID.class),
                        eq("meeting.recording-deletion.completed"),
                        anyString(), eq("SUCCESS"), anyMap());
        MeetingRecordingDeletionTransactions first = transactionsAt(now, failingAudit);
        MeetingRecordingDeletionTransactions second = transactionsAt(now, audit);
        DeletionCycle cycle = first.claimCycle();

        assertThat(second.claimCycle()).isNull();
        PreparedDeletion prepared = first.prepareNext(
                cycle, "GOVERNED_EGRESS", true);
        assertThatThrownBy(() -> first.succeed(prepared, receipt(prepared, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_recording_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.recording-deletion.completed'
                """, Integer.class)).isZero();
    }

    @Test
    void legacyQuarantineWithUnknownRetentionIsStillDeletedWithoutInventingProvenance() {
        UUID legacyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_artifacts (
                    artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
                    storage_provider, object_key, content_type, size_bytes, sha256,
                    retention_until, metadata, created_at, updated_at)
                VALUES (?, 1, ?, 'RECORDING', 'UNAVAILABLE', 'BROKER', ?,
                        'video/mp4', 2048, NULL, NULL,
                        '{"reason":"LEGACY_RECORDING_PROVENANCE_MISSING"}'::jsonb,
                        ?, ?)
                """, legacyId, fixture.meetingId(), "legacy/opaque-object",
                now.minusDays(10), now.minusDays(10));
        CapturingDeletionProvider provider = new CapturingDeletionProvider(now);
        MeetingRecordingDeletionService service = service(provider, transactionsAt(now, audit));

        assertThat(service.purgeExpired()).isEqualTo(2);

        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, legacyId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT object_key IS NULL FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Boolean.class, legacyId)).isTrue();
    }

    @Test
    void advancingClockRenewsTheCycleBeforeEachDeletion() {
        UUID secondId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_artifacts (
                    artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
                    storage_provider, object_key, content_type, size_bytes,
                    retention_until, metadata, created_at, updated_at)
                VALUES (?, 1, ?, 'RECORDING', 'UNAVAILABLE', 'BROKER', ?,
                        'video/mp4', 2048, NULL, '{}'::jsonb, ?, ?)
                """, secondId, fixture.meetingId(), "legacy/second-object",
                now.minusDays(10), now.minusDays(10));
        AdvancingClock clock = new AdvancingClock(now.toInstant(), Duration.ofSeconds(5));
        MeetingRecordingDeletionTransactions transactions = transactional(
                new MeetingRecordingDeletionTransactions(
                        repository, properties, audit, clock));
        CapturingDeletionProvider provider = new CapturingDeletionProvider(now);

        assertThat(service(provider, transactions).purgeExpired()).isEqualTo(2);
        assertThat(provider.deleteCount).isEqualTo(2);
        assertThat(provider.transactionObserved).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_artifacts
                 WHERE artifact_id IN (?, ?) AND artifact_state = 'DELETED'
                """, Integer.class, fixture.artifactId(), secondId)).isEqualTo(2);
    }

    @Test
    void localFailureLatchBlocksReadinessWhenCycleFailureCannotBePersisted() {
        jdbc.update("""
                UPDATE vm_meeting_artifacts SET retention_until = ?
                 WHERE artifact_id = ?
                """, now.plusDays(1), fixture.artifactId());
        jdbc.update("""
                UPDATE vm_meeting_recording_deletion_health
                   SET last_success_at = ?, last_attempt_at = ?, updated_at = ?,
                       last_provider_code = 'GOVERNED_EGRESS'
                 WHERE health_key = 'RECORDING_RETENTION'
                """, now, now, now);
        assertThat(deletionReadiness.ready()).isTrue();
        MeetingRecordingDeletionTransactions failing = mock(
                MeetingRecordingDeletionTransactions.class);
        DeletionCycle cycle = new DeletionCycle(
                UUID.randomUUID(), properties.getWorkerId(),
                now.plus(properties.getLeaseDuration()));
        when(failing.claimCycle()).thenReturn(cycle);
        when(failing.renewCycle(cycle)).thenThrow(new IllegalStateException("db unavailable"));
        doThrow(new IllegalStateException("db unavailable"))
                .when(failing).failCycle(cycle, "RECORDING_RETENTION_FAILURE");

        assertThat(service(new CapturingDeletionProvider(now), failing).purgeExpired()).isZero();

        assertThat(deletionReadiness.localFailureAt()).isEqualTo(now);
        assertThat(deletionReadiness.ready()).isFalse();
    }

    @Test
    void leaseMustOutliveTheBoundedProviderTimeoutWithSafetyMargin() {
        MeetingRecordingHttpProperties http = new MeetingRecordingHttpProperties();
        http.setRequestTimeout(Duration.ofSeconds(30));
        properties.setLeaseDuration(Duration.ofSeconds(30));

        assertThat(new MeetingRecordingDeletionReadiness(
                repository, properties, http,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)).validConfiguration())
                .isFalse();
    }

    @Test
    void staleProviderReceiptBeforeCommandRequestIsRejected() {
        MeetingRecordingDeletionTransactions transactions = transactionsAt(now, audit);
        DeletionCycle cycle = transactions.claimCycle();
        PreparedDeletion prepared = transactions.prepareNext(
                cycle, "GOVERNED_EGRESS", true);

        assertThatThrownBy(() -> transactions.succeed(
                prepared, receipt(prepared, now.minusMinutes(1))))
                .isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("AVAILABLE");
    }

    private Fixture fixture() {
        UUID meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'ENDED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        long actor = jdbc.queryForObject("""
                SELECT organizer_user_id FROM vm_meetings
                 WHERE tenant_id = 1 AND meeting_id = ?
                """, Long.class, meetingId);
        UUID noticeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_content_notices (
                    notice_id, tenant_id, meeting_id, notice_revision,
                    recording_disclosed, transcription_disclosed,
                    ai_summary_disclosed, published_by)
                VALUES (?, 1, ?, 1, TRUE, FALSE, FALSE, ?)
                """, noticeId, meetingId, actor);
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_recording_sessions (
                    recording_session_id, tenant_id, meeting_id, plan_version,
                    notice_id, recording_state, requested_at, requested_by,
                    stop_requested_at, stop_requested_by, started_at, stopped_at,
                    artifact_retention_days, recording_provider_code,
                    recording_processing_region, stop_consent_snapshot_sha256,
                    updated_at)
                VALUES (?, 1, ?, 0, ?, 'STOPPED', ?, ?, ?, ?, ?, ?, 30,
                        'GOVERNED_EGRESS', 'ap-northeast-2', ?, ?)
                """, sessionId, meetingId, noticeId,
                now.minusDays(31), actor, now.minusDays(30), actor,
                now.minusDays(31).plusMinutes(1), now.minusDays(30),
                "b".repeat(64), now.minusDays(30));
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'RECORDING'
                """, UUID.class, meetingId);
        String objectKey = "tenant-1/recordings/opaque-" + artifactId;
        String sourceSha = "a".repeat(64);
        jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = 'BROKER',
                       object_key = ?, content_type = 'video/mp4', size_bytes = 1024,
                       sha256 = ?, retention_until = ?, metadata = '{}'::jsonb,
                       processing_region = 'ap-northeast-2', content_notice_id = ?,
                       consent_snapshot_sha256 = ?, recording_session_id = ?,
                       recording_plan_version = 0,
                       recording_provider_code = 'GOVERNED_EGRESS',
                       recording_finalization_idempotency_key = 'recording-finalize-0001',
                       recording_finalization_request_sha256 = ?,
                       recording_finalized_at = ?, recording_finalized_by = ?,
                       version = version + 1, updated_at = ?
                 WHERE artifact_id = ?
                """, objectKey, sourceSha, now.minusSeconds(1), noticeId,
                "b".repeat(64), sessionId, "c".repeat(64),
                now.minusDays(30), actor, now.minusDays(30), artifactId);
        long version = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Long.class, artifactId);
        return new Fixture(meetingId, artifactId, objectKey, sourceSha, version);
    }

    private MeetingRecordingDeletionTransactions transactionsAt(
            OffsetDateTime time, VideoMeetingAuditRecorder recorder) {
        return transactional(new MeetingRecordingDeletionTransactions(
                repository, properties, recorder,
                Clock.fixed(time.toInstant(), ZoneOffset.UTC)));
    }

    private MeetingRecordingDeletionService service(
            MeetingRecordingProvider provider,
            MeetingRecordingDeletionTransactions transactions) {
        return new MeetingRecordingDeletionService(
                transactions, properties, provider, deletionReadiness);
    }

    private MeetingRecordingDeletionReadiness readiness(OffsetDateTime time) {
        return new MeetingRecordingDeletionReadiness(
                repository, properties, Clock.fixed(time.toInstant(), ZoneOffset.UTC));
    }

    private MeetingRecordingProvider.DeletionReceipt receipt(
            PreparedDeletion prepared, OffsetDateTime deletedAt) {
        return new MeetingRecordingProvider.DeletionReceipt(
                prepared.artifact().artifactId(), prepared.artifact().version(),
                "provider-delete-" + prepared.command().commandId(), deletedAt);
    }

    private MeetingRecordingDeletionProperties properties() {
        MeetingRecordingDeletionProperties configured =
                new MeetingRecordingDeletionProperties();
        configured.setEnabled(true);
        configured.setBatchSize(10);
        configured.setPollDelay(Duration.ofMinutes(5));
        configured.setLeaseDuration(Duration.ofMinutes(1));
        configured.setWorkerId("recording-retention-test");
        return configured;
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

    private final class CapturingDeletionProvider implements MeetingRecordingProvider {
        private final OffsetDateTime now;
        private boolean fail;
        private int deleteCount;
        private DeleteRequest lastRequest;
        private boolean transactionObserved;
        private boolean commandVisibleBeforeDelete;

        private CapturingDeletionProvider(OffsetDateTime now) {
            this.now = now;
        }

        @Override
        public Capability capability() {
            transactionObserved = transactionObserved
                    || TransactionSynchronizationManager.isActualTransactionActive();
            return new Capability(
                    true, true, true, true, true, true,
                    true, 3_600, true,
                    "ap-northeast-2", "GOVERNED_EGRESS");
        }

        @Override public Receipt start(Command command) { throw new UnsupportedOperationException(); }
        @Override public Receipt stop(Command command) { throw new UnsupportedOperationException(); }
        @Override public AccessTicket issueAccessTicket(AccessRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DeletionReceipt delete(DeleteRequest request) {
            transactionObserved = transactionObserved
                    || TransactionSynchronizationManager.isActualTransactionActive();
            commandVisibleBeforeDelete = jdbc.queryForObject("""
                    SELECT COUNT(*) = 1
                      FROM vm_meeting_recording_deletion_commands
                     WHERE artifact_id = ? AND command_state = 'RUNNING'
                    """, Boolean.class, request.artifactId());
            deleteCount++;
            lastRequest = request;
            if (fail) throw new IllegalStateException("provider unavailable");
            return new DeletionReceipt(
                    request.artifactId(), request.artifactVersion(),
                    "provider-delete-" + request.artifactId(), now);
        }
    }

    private static final class AdvancingClock extends Clock {
        private Instant current;
        private final Duration step;

        private AdvancingClock(Instant current, Duration step) {
            this.current = current;
            this.step = step;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            Instant value = current;
            current = current.plus(step);
            return value;
        }
    }

    private record Fixture(
            UUID meetingId,
            UUID artifactId,
            String objectKey,
            String sourceSha256,
            long artifactVersion) {
    }
}
