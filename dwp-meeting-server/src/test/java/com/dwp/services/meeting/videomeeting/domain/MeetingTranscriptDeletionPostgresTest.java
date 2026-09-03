package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.DeletionCycle;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.PreparedDeletion;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
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
class MeetingTranscriptDeletionPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private MeetingTranscriptDeletionProperties properties;
    private MeetingTranscriptHttpProperties httpProperties;
    private MeetingTranscriptDeletionRepository repository;
    private MeetingTranscriptDeletionReadiness readiness;
    private VideoMeetingAuditRecorder audit;
    private OffsetDateTime now;
    private Fixture fixture;

    @BeforeEach
    void setup() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        properties = new MeetingTranscriptDeletionProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setPollDelay(Duration.ofMinutes(5));
        properties.setLeaseDuration(Duration.ofSeconds(30));
        properties.setWorkerId("transcript-retention-test");
        httpProperties = new MeetingTranscriptHttpProperties();
        httpProperties.setProvider("http");
        httpProperties.setRequestTimeout(Duration.ofSeconds(1));
        repository = new MeetingTranscriptDeletionRepository(jdbc);
        readiness = new MeetingTranscriptDeletionReadiness(
                repository, properties, httpProperties,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        audit = new VideoMeetingAuditRecorder(new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-meeting-server", "transcript-deletion-test", "test"));
        fixture = fixture();
    }

    @Test
    void successClearsLocatorAndCommitsArtifactCommandAndAuditOutsideProviderTransaction() {
        CapturingSource source = source();
        MeetingTranscriptDeletionTransactions transactions = transactionsAt(now, audit);

        assertThat(service(source, transactions).purgeExpired()).isOne();
        assertThat(source.transactionObserved).isFalse();
        assertThat(source.deleteCount).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT object_key IS NULL AND storage_provider IS NULL
                  FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Boolean.class, fixture.artifactId())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_transcript_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.transcript-deletion.completed'
                """, Integer.class)).isOne();
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void concurrentClaimAndExpiredReclaimFenceTheOldWorker() {
        MeetingTranscriptDeletionTransactions first = transactionsAt(now, audit);
        DeletionCycle oldCycle = first.claimCycle();
        PreparedDeletion oldPrepared = first.prepareNext(
                oldCycle, "TRANSCRIPT_BROKER", "BROKER", true);
        assertThat(transactionsAt(now, audit).claimCycle()).isNull();

        OffsetDateTime recoveredAt = now.plusSeconds(31);
        MeetingTranscriptDeletionTransactions recovered = transactionsAt(recoveredAt, audit);
        DeletionCycle newCycle = recovered.claimCycle();
        PreparedDeletion newPrepared = recovered.prepareNext(
                newCycle, "TRANSCRIPT_BROKER", "BROKER", true);
        assertThat(newPrepared.command().commandId())
                .isEqualTo(oldPrepared.command().commandId());
        assertThat(newPrepared.command().attemptCount()).isEqualTo(2);
        assertThatThrownBy(() -> first.succeed(oldPrepared, receipt(oldPrepared, now)))
                .isInstanceOf(BaseException.class);
        recovered.succeed(newPrepared, receipt(newPrepared, recoveredAt));
        recovered.completeCycle(newCycle, "TRANSCRIPT_BROKER", "BROKER");
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("DELETED");
    }

    @Test
    void crashAfterProviderSuccessReusesTheOriginalReceiptAfterLeaseReclaim() {
        MeetingTranscriptDeletionTransactions crashedWorker = transactionsAt(now, audit);
        DeletionCycle crashedCycle = crashedWorker.claimCycle();
        PreparedDeletion crashedPrepared = crashedWorker.prepareNext(
                crashedCycle, "TRANSCRIPT_BROKER", "BROKER", true);
        MeetingTranscriptSource.DeletionReceipt durableProviderReceipt =
                receipt(crashedPrepared, now);

        OffsetDateTime recoveredAt = now.plusSeconds(31);
        MeetingTranscriptDeletionTransactions recoveredWorker =
                transactionsAt(recoveredAt, audit);
        DeletionCycle recoveredCycle = recoveredWorker.claimCycle();
        PreparedDeletion recoveredPrepared = recoveredWorker.prepareNext(
                recoveredCycle, "TRANSCRIPT_BROKER", "BROKER", true);

        assertThat(recoveredPrepared.command().commandId())
                .isEqualTo(crashedPrepared.command().commandId());
        assertThat(recoveredPrepared.command().attemptCount()).isEqualTo(2);
        assertThat(recoveredPrepared.command().requestedAt())
                .isEqualTo(crashedPrepared.command().requestedAt());

        recoveredWorker.succeed(recoveredPrepared, durableProviderReceipt);
        recoveredWorker.completeCycle(
                recoveredCycle, "TRANSCRIPT_BROKER", "BROKER");

        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_transcript_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("SUCCEEDED");
    }

    @Test
    void auditFailureRollsBackTerminalProjectionAndRetainsRetryEvidence() {
        VideoMeetingAuditRecorder failing = mock(VideoMeetingAuditRecorder.class);
        doThrow(new IllegalStateException("audit unavailable")).when(failing)
                .transcriptDeletion(anyLong(), any(UUID.class), any(UUID.class),
                        eq("meeting.transcript-deletion.completed"), anyString(),
                        eq("SUCCESS"), anyMap());
        MeetingTranscriptDeletionTransactions transactions = transactionsAt(now, failing);
        DeletionCycle cycle = transactions.claimCycle();
        PreparedDeletion prepared = transactions.prepareNext(
                cycle, "TRANSCRIPT_BROKER", "BROKER", true);

        assertThatThrownBy(() -> transactions.succeed(prepared, receipt(prepared, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT command_state FROM vm_meeting_transcript_deletion_commands
                 WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("RUNNING");
    }

    @Test
    void providerChangeAndUrlLikeLocatorAreFailClosed() {
        MeetingTranscriptDeletionTransactions transactions = transactionsAt(now, audit);
        DeletionCycle cycle = transactions.claimCycle();
        assertThatThrownBy(() -> transactions.prepareNext(
                cycle, "OTHER_TRANSCRIPT", "BROKER", true))
                .isInstanceOf(BaseException.class);
        jdbc.update("""
                UPDATE vm_meeting_artifacts SET object_key = ? WHERE artifact_id = ?
                """, "https://attacker.example/raw", fixture.artifactId());
        DeletionCycle next = transactionsAt(now.plusSeconds(31), audit).claimCycle();
        assertThatThrownBy(() -> transactionsAt(now.plusSeconds(31), audit).prepareNext(
                next, "TRANSCRIPT_BROKER", "BROKER", true))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void readinessFailsClosedForBacklogAndLastFailure() {
        assertThat(readiness.ready()).isFalse();
        jdbc.update("""
                UPDATE vm_meeting_artifacts SET retention_until = ? WHERE artifact_id = ?
                """, now.plusDays(1), fixture.artifactId());
        jdbc.update("""
                UPDATE vm_meeting_transcript_deletion_health
                   SET last_success_at = ?, last_provider_code = 'TRANSCRIPT_BROKER',
                       last_storage_provider_code = 'BROKER'
                 WHERE health_key = 'TRANSCRIPT_RETENTION'
                """, now);
        assertThat(readiness.ready()).isTrue();
        jdbc.update("""
                UPDATE vm_meeting_transcript_deletion_health
                   SET last_failure_at = ?, last_failure_code = 'BROKER_FAILURE'
                 WHERE health_key = 'TRANSCRIPT_RETENTION'
                """, now);
        assertThat(readiness.ready()).isFalse();
    }

    @Test
    void staleProviderReceiptBeforeCommandRequestIsRejected() {
        MeetingTranscriptDeletionTransactions transactions = transactionsAt(now, audit);
        DeletionCycle cycle = transactions.claimCycle();
        PreparedDeletion prepared = transactions.prepareNext(
                cycle, "TRANSCRIPT_BROKER", "BROKER", true);

        assertThatThrownBy(() -> transactions.succeed(
                prepared, receipt(prepared, now.minusMinutes(1))))
                .isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("""
                SELECT artifact_state FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, String.class, fixture.artifactId())).isEqualTo("AVAILABLE");
    }

    @Test
    void advancingClockRenewsCycleBeforeEachTranscriptDeletion() {
        UUID secondMeeting = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'ENDED' AND meeting_id <> ?
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class, fixture.meetingId());
        UUID secondArtifact = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                """, UUID.class, secondMeeting);
        jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'UNAVAILABLE', storage_provider = 'BROKER',
                       object_key = ?, content_type = 'application/json', size_bytes = 128,
                       sha256 = ?, retention_until = ?, server_side_processing_allowed = FALSE,
                       transcript_plan_version = 0,
                       transcript_provider_code = 'TRANSCRIPT_BROKER',
                       transcript_storage_provider_code = 'BROKER',
                       version = version + 1, updated_at = ?
                 WHERE artifact_id = ?
                """, "legacy/transcript/" + secondArtifact, "c".repeat(64),
                now.minusSeconds(1), now, secondArtifact);
        AdvancingClock clock = new AdvancingClock(now.toInstant(), Duration.ofSeconds(5));
        MeetingTranscriptDeletionTransactions transactions = transactional(
                new MeetingTranscriptDeletionTransactions(
                        repository, properties, httpProperties, audit, clock));
        CapturingSource source = source();

        assertThat(service(source, transactions).purgeExpired()).isEqualTo(2);
        assertThat(source.deleteCount).isEqualTo(2);
        assertThat(source.transactionObserved).isFalse();
    }

    private Fixture fixture() {
        UUID meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'ENDED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        UUID artifactId = jdbc.queryForObject("""
                SELECT artifact_id FROM vm_meeting_artifacts
                 WHERE tenant_id = 1 AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                """, UUID.class, meetingId);
        String objectKey = "legacy/transcript/" + artifactId;
        jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = 'BROKER',
                       object_key = ?, content_type = 'application/json', size_bytes = 512,
                       sha256 = ?, retention_until = ?, server_side_processing_allowed = FALSE,
                       transcript_plan_version = 0,
                       transcript_provider_code = 'TRANSCRIPT_BROKER',
                       transcript_storage_provider_code = 'BROKER',
                       version = version + 1, updated_at = ?
                 WHERE artifact_id = ?
                """, objectKey, "a".repeat(64), now.minusSeconds(1), now, artifactId);
        long version = jdbc.queryForObject("""
                SELECT version FROM vm_meeting_artifacts WHERE artifact_id = ?
                """, Long.class, artifactId);
        return new Fixture(meetingId, artifactId, objectKey, version);
    }

    private CapturingSource source() {
        MeetingTranscriptSource source = mock(MeetingTranscriptSource.class);
        MeetingTranscriptSource.RetentionCapability capability =
                new MeetingTranscriptSource.RetentionCapability(
                        true, true, true, true, true, true, 300, true,
                        "TRANSCRIPT_BROKER", "BROKER");
        when(source.retentionCapability()).thenReturn(capability);
        return new CapturingSource(source, capability);
    }

    private MeetingTranscriptDeletionService service(
            CapturingSource source, MeetingTranscriptDeletionTransactions transactions) {
        return new MeetingTranscriptDeletionService(
                transactions, properties, source, readiness);
    }

    private MeetingTranscriptDeletionTransactions transactionsAt(
            OffsetDateTime time, VideoMeetingAuditRecorder recorder) {
        return transactional(new MeetingTranscriptDeletionTransactions(
                repository, properties, httpProperties, recorder,
                Clock.fixed(time.toInstant(), ZoneOffset.UTC)));
    }

    private MeetingTranscriptSource.DeletionReceipt receipt(
            PreparedDeletion prepared, OffsetDateTime deletedAt) {
        return new MeetingTranscriptSource.DeletionReceipt(
                prepared.artifact().artifactId(), prepared.artifact().version(),
                "delete-" + prepared.command().commandId(), deletedAt);
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

    private record Fixture(UUID meetingId, UUID artifactId, String objectKey, long version) { }

    private static final class AdvancingClock extends Clock {
        private Instant current;
        private final Duration step;

        private AdvancingClock(Instant current, Duration step) {
            this.current = current;
            this.step = step;
        }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() {
            Instant value = current;
            current = current.plus(step);
            return value;
        }
    }

    private static final class CapturingSource implements MeetingTranscriptSource {
        private final MeetingTranscriptSource delegate;
        private final RetentionCapability capability;
        private boolean transactionObserved;
        private int deleteCount;

        private CapturingSource(
                MeetingTranscriptSource delegate, RetentionCapability capability) {
            this.delegate = delegate;
            this.capability = capability;
        }

        @Override
        public boolean available() { return true; }

        @Override
        public java.util.List<com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.TranscriptSegment>
                read(ReadContext context) { return java.util.List.of(); }

        @Override
        public RetentionCapability retentionCapability() { return capability; }

        @Override
        public DeletionReceipt delete(DeleteRequest request) {
            transactionObserved = TransactionSynchronizationManager.isActualTransactionActive();
            deleteCount++;
            return new DeletionReceipt(
                    request.artifactId(), request.artifactVersion(),
                    "delete-" + request.artifactId(), OffsetDateTime.now(ZoneOffset.UTC));
        }
    }
}
