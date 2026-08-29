package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationType;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Preparation;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class VideoMeetingLifecyclePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private VideoMeetingLifecycleOperationRepository operations;
    private MeetingMediaUpgradeRepository upgrades;
    private VideoMeetingRepository meetings;
    private MeetingMediaProvider mediaProvider;
    private VideoMeetingAuditRecorder audit;
    private MeetingMediaProperties properties;
    private OffsetDateTime now;

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
        operations = new VideoMeetingLifecycleOperationRepository(jdbc);
        upgrades = new MeetingMediaUpgradeRepository(jdbc);
        meetings = new VideoMeetingRepository(jdbc, new ObjectMapper());
        mediaProvider = mock(MeetingMediaProvider.class);
        audit = mock(VideoMeetingAuditRecorder.class);
        properties = new MeetingMediaProperties();
        properties.setLifecycleOperationLease(Duration.ofMinutes(2));
        now = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    void terminalProjectionEventAndOperationRollBackWhenAuditFails() {
        UUID meetingId = scheduledMeetingId();
        MeetingRequestContext.Subject subject = subject(meetingId);
        long expectedVersion = meetingVersion(meetingId);
        UUID incarnation = UUID.nameUUIDFromBytes((
                "dwp-meeting-media-v1|1|" + meetingId + "|lifecycle-start-001")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MeetingMediaProvider.PreparedRoom room = new MeetingMediaProvider.PreparedRoom(
                "LIVEKIT", "dwp-meeting-t1-" + meetingId.toString().replace("-", ""),
                1L, meetingId, incarnation);
        when(mediaProvider.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300));
        when(mediaProvider.planRoom(meetingId, 1L, incarnation)).thenReturn(room);
        VideoMeetingLifecycleTransactions lifecycle = transactionsAt(now);
        Preparation prepared = transaction.execute(status -> lifecycle.prepareStart(
                subject, meetingId, expectedVersion, "lifecycle-start-001", "corr-start"));

        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).meetingLifecycle(
                        org.mockito.ArgumentMatchers.eq(subject),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("meeting.started"),
                        org.mockito.ArgumentMatchers.eq("corr-start"),
                        org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(() -> transaction.execute(status ->
                lifecycle.completeStart(subject, prepared.operation())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meetingState(meetingId)).isEqualTo("SCHEDULED");
        assertThat(operationState(prepared.operation().operationId())).isEqualTo("RUNNING");
        assertThat(eventCount(meetingId, "STARTED")).isZero();

        reset(audit);
        var completed = transaction.execute(status ->
                lifecycle.completeStart(subject, prepared.operation()));
        assertThat(completed.detail().meeting().lifecycleState().name()).isEqualTo("LIVE");
        assertThat(meetingState(meetingId)).isEqualTo("LIVE");
        assertThat(operationState(prepared.operation().operationId())).isEqualTo("SUCCEEDED");
        assertThat(eventCount(meetingId, "STARTED")).isOne();
    }

    @Test
    void endProjectionAndAuditShareTheSameFencedCommit() {
        UUID meetingId = liveMeetingId();
        activateMigratedLiveRoom(meetingId);
        MeetingRequestContext.Subject subject = subject(meetingId);
        long expectedVersion = meetingVersion(meetingId);
        when(mediaProvider.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300));
        VideoMeetingLifecycleTransactions lifecycle = transactionsAt(now);
        Preparation prepared = transaction.execute(status -> lifecycle.prepareEnd(
                subject, meetingId, expectedVersion, "lifecycle-end-001", "corr-end"));

        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).meetingLifecycle(
                        org.mockito.ArgumentMatchers.eq(subject),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("meeting.ended"),
                        org.mockito.ArgumentMatchers.eq("corr-end"),
                        org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(() -> transaction.execute(status ->
                lifecycle.completeEnd(subject, prepared.operation())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meetingState(meetingId)).isEqualTo("LIVE");
        assertThat(operationState(prepared.operation().operationId())).isEqualTo("RUNNING");
        assertThat(eventCount(meetingId, "ENDED")).isZero();

        reset(audit);
        var completed = transaction.execute(status ->
                lifecycle.completeEnd(subject, prepared.operation()));
        assertThat(completed.detail().meeting().lifecycleState().name()).isEqualTo("ENDED");
        assertThat(meetingState(meetingId)).isEqualTo("ENDED");
        assertThat(operationState(prepared.operation().operationId())).isEqualTo("SUCCEEDED");
        assertThat(eventCount(meetingId, "ENDED")).isOne();
    }

    @Test
    void migratedLegacyLiveRoomIsFailClosedWithADurableExplicitIncarnationTarget() {
        UUID meetingId = liveMeetingId();
        UUID expected = UUID.nameUUIDFromBytes((
                "dwp-meeting-media-compat-v1|1|" + meetingId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        UUID stored = jdbc.queryForObject("""
                SELECT media_incarnation FROM vm_meetings WHERE meeting_id = ?
                """, UUID.class, meetingId);
        String accessState = jdbc.queryForObject("""
                SELECT media_access_state FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId);
        String legacyRoom = jdbc.queryForObject("""
                SELECT room_name FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId);
        String targetRoom = jdbc.queryForObject("""
                SELECT target_room_name FROM vm_meeting_media_upgrades
                 WHERE meeting_id = ?
                """, String.class, meetingId);
        String upgradeState = jdbc.queryForObject("""
                SELECT upgrade_state FROM vm_meeting_media_upgrades
                 WHERE meeting_id = ?
                """, String.class, meetingId);
        Integer staleJoined = jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_participants
                 WHERE meeting_id = ? AND attendance_state = 'JOINED'
                """, Integer.class, meetingId);

        assertThat(stored).isEqualTo(expected);
        assertThat(accessState).isEqualTo("MIGRATING");
        assertThat(upgradeState).isEqualTo("PENDING");
        assertThat(staleJoined).isZero();
        assertThat(targetRoom).isEqualTo(
                "dwp-meeting-t1-" + meetingId.toString().replace("-", "")
                        + "-i" + expected.toString().replace("-", ""));
        assertThat(targetRoom).isNotEqualTo(legacyRoom);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vm_meeting_events (tenant_id, meeting_id, event_type)
                VALUES (1, ?, 'TOKEN_ISSUED')
                """, meetingId)).hasMessageContaining("token issuance is fenced");
    }

    @Test
    void legacyUpgradeIsFencedAndActivatesOnlyAfterLegacyRoomCleanup() {
        UUID meetingId = liveMeetingId();
        String legacyRoom = jdbc.queryForObject("""
                SELECT room_name FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId);
        MeetingMediaUpgradeRepository.UpgradeClaim stale = transaction.execute(status ->
                upgrades.claimProvision(
                        UUID.randomUUID(), now, now.plusSeconds(30), 8).orElseThrow());
        MeetingMediaUpgradeRepository.UpgradeClaim winner = transaction.execute(status ->
                upgrades.claimProvision(
                        UUID.randomUUID(), now.plusMinutes(1), now.plusMinutes(3), 8)
                        .orElseThrow());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                upgrades.switchToTarget(stale, now.plusMinutes(1))))
                .isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("""
                SELECT room_name FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId)).isEqualTo(legacyRoom);

        transaction.executeWithoutResult(status ->
                upgrades.switchToTarget(winner, now.plusMinutes(1).plusSeconds(1)));
        assertThat(jdbc.queryForObject("""
                SELECT media_access_state FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId)).isEqualTo("MIGRATING");
        assertThat(jdbc.queryForObject("""
                SELECT room_name FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId)).isEqualTo(winner.targetRoomName());
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM vm_meeting_media_upgrades
                 WHERE meeting_id = ?
                """, Integer.class, meetingId)).isZero();

        MeetingMediaUpgradeRepository.UpgradeClaim cleanup = transaction.execute(status ->
                upgrades.claimCleanup(
                        UUID.randomUUID(), now.plusMinutes(12), now.plusMinutes(14), 8)
                        .orElseThrow());
        transaction.executeWithoutResult(status ->
                upgrades.finalizeActive(cleanup, now.plusMinutes(12).plusSeconds(1)));

        assertThat(jdbc.queryForObject("""
                SELECT media_access_state FROM vm_meetings WHERE meeting_id = ?
                """, String.class, meetingId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT upgrade_state FROM vm_meeting_media_upgrades
                 WHERE meeting_id = ?
                """, String.class, meetingId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.update("""
                INSERT INTO vm_meeting_events (tenant_id, meeting_id, event_type)
                VALUES (1, ?, 'TOKEN_ISSUED')
                """, meetingId)).isOne();
    }

    @Test
    void v21RetryableStartIsDeterministicallyReboundAndLegacyRoomIsQueuedForCleanup() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load().clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target(MigrationVersion.fromVersion("21"))
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        UUID meetingId = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'SCHEDULED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        long actor = organizer(meetingId);
        long version = meetingVersion(meetingId);
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = "migration-start-001";
        String legacyRoom = "dwp-meeting-legacy-v21-start";
        OffsetDateTime requestedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3);
        jdbc.update("""
                INSERT INTO vm_meeting_media_operations (
                    operation_id, tenant_id, meeting_id, operation_type,
                    operation_state, actor_user_id, expected_meeting_version,
                    idempotency_key, request_sha256, correlation_id,
                    execution_fence, lease_expires_at, attempt_count,
                    provider_code, provider_room_name, requested_at)
                VALUES (?, 1, ?, 'START', 'RUNNING', ?, ?, ?, ?, ?, ?, ?, 1,
                        'LIVEKIT', ?, ?)
                """, operationId, meetingId, actor, version, idempotencyKey,
                "a".repeat(64), "corr-migration-start", UUID.randomUUID(),
                requestedAt.plusMinutes(1), legacyRoom, requestedAt);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();

        UUID expectedIncarnation = UUID.nameUUIDFromBytes((
                "dwp-meeting-media-v1|1|" + meetingId + "|" + idempotencyKey)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expectedRoom = "dwp-meeting-t1-"
                + meetingId.toString().replace("-", "") + "-i"
                + expectedIncarnation.toString().replace("-", "");
        Map<String, Object> migrated = jdbc.queryForMap("""
                SELECT room_incarnation, provider_room_name, request_sha256
                  FROM vm_meeting_media_operations WHERE operation_id = ?
                """, operationId);
        assertThat(migrated.get("room_incarnation")).isEqualTo(expectedIncarnation);
        assertThat(migrated.get("provider_room_name")).isEqualTo(expectedRoom);
        assertThat(migrated.get("request_sha256")).isEqualTo(
                VideoMeetingCommandPolicy.requestHash(
                        OperationType.START, meetingId, version, "LIVEKIT", expectedRoom));
        assertThat(jdbc.queryForMap("""
                SELECT provider_room_name, processing_state, reason_code
                  FROM vm_meeting_provider_events
                 WHERE provider_event_id = ?
                """, "migration-v22-start-" + operationId))
                .containsEntry("provider_room_name", legacyRoom)
                .containsEntry("processing_state", "CLEANUP_REQUIRED")
                .containsEntry("reason_code", "MIGRATION_LEGACY_START_ROOM");
    }

    @Test
    void onlyOneScheduledRecoveryClaimWinsAndStaleWorkerCannotFinalize() throws Exception {
        UUID meetingId = scheduledMeetingId();
        long actor = organizer(meetingId);
        MediaOperation stale = new MediaOperation(
                UUID.randomUUID(), 1L, meetingId, OperationType.START,
                OperationState.RUNNING, actor, meetingVersion(meetingId),
                "lifecycle-start-002", "a".repeat(64), "corr-start",
                UUID.randomUUID(), now.minusMinutes(1), 1,
                "LIVEKIT", "dwp-meeting-t1-" + meetingId.toString().replace("-", ""),
                UUID.randomUUID());
        MediaOperation stored = transaction.execute(status -> operations.insert(
                stale, now.minusMinutes(3)).orElseThrow());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MediaOperation> first = executor.submit(() -> claimRecoverable(
                    UUID.randomUUID(), ready, start));
            Future<MediaOperation> second = executor.submit(() -> claimRecoverable(
                    UUID.randomUUID(), ready, start));
            ready.await();
            start.countDown();
            List<MediaOperation> results = java.util.Arrays.asList(first.get(), second.get());
            MediaOperation winner = results.stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertThat(results.stream().filter(java.util.Objects::isNull).count()).isOne();
            assertThat(winner.operationId()).isEqualTo(stored.operationId());
            assertThat(winner.attemptCount()).isEqualTo(2);

            assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                    operations.succeed(stored, now.plusSeconds(1))))
                    .isInstanceOf(BaseException.class);
            transaction.executeWithoutResult(status ->
                    operations.succeed(winner, now.plusSeconds(1)));
            assertThat(operationState(winner.operationId())).isEqualTo("SUCCEEDED");
        } finally {
            executor.shutdownNow();
        }
    }

    private MediaOperation claimRecoverable(
            UUID fence,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        TransactionTemplate isolated = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        VideoMeetingLifecycleOperationRepository contender =
                new VideoMeetingLifecycleOperationRepository(new JdbcTemplate(dataSource));
        return isolated.execute(status -> contender.claimRecoverable(
                fence, now, now.plusMinutes(2), 8).orElse(null));
    }

    private VideoMeetingLifecycleTransactions transactionsAt(OffsetDateTime at) {
        return new VideoMeetingLifecycleTransactions(
                meetings, operations, mediaProvider, properties, audit,
                Clock.fixed(at.toInstant(), ZoneOffset.UTC));
    }

    private UUID scheduledMeetingId() {
        return jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'SCHEDULED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
    }

    private UUID liveMeetingId() {
        return jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'LIVE'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
    }

    private void activateMigratedLiveRoom(UUID meetingId) {
        MeetingMediaUpgradeRepository.UpgradeClaim provision = transaction.execute(status ->
                upgrades.claimProvision(
                        UUID.randomUUID(), now, now.plusMinutes(2), 8).orElseThrow());
        assertThat(provision.meetingId()).isEqualTo(meetingId);
        transaction.executeWithoutResult(status ->
                upgrades.switchToTarget(provision, now.plusSeconds(1)));
        MeetingMediaUpgradeRepository.UpgradeClaim cleanup = transaction.execute(status ->
                upgrades.claimCleanup(
                        UUID.randomUUID(), now.plusMinutes(12), now.plusMinutes(14), 8)
                        .orElseThrow());
        transaction.executeWithoutResult(status ->
                upgrades.finalizeActive(cleanup, now.plusMinutes(12).plusSeconds(1)));
    }

    private long meetingVersion(UUID meetingId) {
        return jdbc.queryForObject(
                "SELECT version FROM vm_meetings WHERE meeting_id = ?",
                Long.class, meetingId);
    }

    private long organizer(UUID meetingId) {
        return jdbc.queryForObject(
                "SELECT organizer_user_id FROM vm_meetings WHERE meeting_id = ?",
                Long.class, meetingId);
    }

    private MeetingRequestContext.Subject subject(UUID meetingId) {
        long actor = organizer(meetingId);
        UUID personId = jdbc.queryForObject("""
                SELECT organizer_person_public_id FROM vm_meetings WHERE meeting_id = ?
                """, UUID.class, meetingId);
        String displayName = jdbc.queryForObject(
                "SELECT organizer_name FROM vm_meetings WHERE meeting_id = ?",
                String.class, meetingId);
        return new MeetingRequestContext.Subject(
                actor, 1L, personId, displayName, Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:UPDATE"), Set.of("SKAX_ALL_EMPLOYEES"));
    }

    private String meetingState(UUID meetingId) {
        return jdbc.queryForObject(
                "SELECT lifecycle_state FROM vm_meetings WHERE meeting_id = ?",
                String.class, meetingId);
    }

    private String operationState(UUID operationId) {
        return jdbc.queryForObject("""
                SELECT operation_state FROM vm_meeting_media_operations
                 WHERE operation_id = ?
                """, String.class, operationId);
    }

    private int eventCount(UUID meetingId, String eventType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_events
                 WHERE meeting_id = ? AND event_type = ?
                """, Integer.class, meetingId, eventType);
    }
}
