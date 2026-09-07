package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.meeting.videomeeting.audit.MeetingChatRetentionAuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class MeetingChatRetentionPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MeetingChatRetentionRepository repository;
    private MeetingChatRetentionAuditRecorder retentionAudit;
    private MeetingChatRetentionTransactions retentionTransactions;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void prepareRetention() {
        repository = new MeetingChatRetentionRepository(jdbc);
        retentionAudit = spy(new MeetingChatRetentionAuditRecorder(
                new AuditOutboxRecorder(
                        new NamedParameterJdbcTemplate(dataSource), mapper,
                        "dwp-meeting-server", "test", "test")));
        retentionTransactions = new MeetingChatRetentionTransactions(repository, retentionAudit);
    }

    @Test
    void expiredEndedMessagesLosePlaintextReasonAndCommandHashesAcrossTenants() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID tenantOneMeeting = endedMeeting(1);
        UUID tenantTwoMeeting = createTenantTwoEndedMeeting(now);
        String firstText = "Project Solstice confidential launch plan";
        String secondText = "Tenant two confidential acquisition plan";
        String sensitiveDeletionReason = "Deleted because acquisition codename leaked";
        UUID first = insertMessage(1, tenantOneMeeting, now.minusDays(1), firstText);
        UUID second = insertMessage(2, tenantTwoMeeting, now.minusDays(1), secondText);
        jdbc.update("""
                UPDATE vm_meeting_chat_messages
                   SET message_state = 'DELETED', message_text = NULL,
                       deleted_at = ?, deleted_by = 202,
                       deletion_reason = ?, updated_at = ?
                 WHERE message_id = ?
                """, now.minusHours(6), sensitiveDeletionReason, now.minusHours(6), second);
        insertCommand(1, tenantOneMeeting, first, "CHAT_SEND", "a".repeat(64));
        insertCommand(2, tenantTwoMeeting, second, "CHAT_DELETE", "b".repeat(64));
        UUID future = insertMessage(
                1, tenantOneMeeting, now.plusDays(1), "future message remains");
        UUID live = insertMessage(
                1, liveMeeting(), now.minusDays(1), "live zero-day message remains");

        UUID fence = UUID.randomUUID();
        assertThat(claim(now, fence, "chat-worker-a")).isTrue();
        MeetingChatRetentionRepository.PurgeResult result = transaction.execute(status ->
                retentionTransactions.purgeAndSucceed(
                        now, 100, UUID.randomUUID(), fence, "chat-worker-a"));

        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(result.overdueRemaining()).isFalse();
        assertPurged(first, 1, tenantOneMeeting);
        assertPurged(second, 2, tenantTwoMeeting);
        assertThat(messageText(future)).isEqualTo("future message remains");
        assertThat(messageText(live)).isEqualTo("live zero-day message remains");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_collaboration_commands
                 WHERE result_resource_id IN (?, ?)
                """, Integer.class, first, second)).isZero();

        String evidence = jdbc.queryForObject("""
                SELECT string_agg(row_to_json(item)::text, '')
                  FROM vm_meeting_chat_retention_evidence item
                """, String.class);
        String audits = jdbc.queryForObject("""
                SELECT string_agg(payload::text, '') FROM sys_audit_outbox
                 WHERE payload->>'action' = 'meeting.chat.retention.purged'
                """, String.class);
        assertThat(evidence).contains(first.toString(), second.toString(), "RETENTION_EXPIRED")
                .doesNotContain(firstText, secondText, sensitiveDeletionReason,
                        "a".repeat(64), "b".repeat(64));
        assertThat(audits).contains(first.toString(), second.toString())
                .doesNotContain(firstText, secondText, sensitiveDeletionReason,
                        "a".repeat(64), "b".repeat(64));
    }

    @Test
    void simultaneousWorkersHaveOneLeaseWinnerAndHealthyActiveWorkStaysReady() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID firstFence = UUID.randomUUID();
        UUID secondFence = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> concurrentClaim(
                    ready, start, now, firstFence, "chat-worker-a"));
            var second = workers.submit(() -> concurrentClaim(
                    ready, start, now, secondFence, "chat-worker-b"));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }
        MeetingChatRetentionRepository.Health claimed = repository.health().orElseThrow();
        transaction.execute(status -> retentionTransactions.purgeAndSucceed(
                now, 100, UUID.randomUUID(), claimed.activeFence(), claimed.activeWorkerId()));

        UUID activeFence = UUID.randomUUID();
        assertThat(claim(now.plusSeconds(1), activeFence, "chat-worker-c")).isTrue();
        assertThat(serviceAt(now.plusSeconds(10), "chat-worker-c").ready()).isTrue();
    }

    @Test
    void expiredLeaseIsReclaimedAndStaleWorkerCannotPurgeOrComplete() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID bootstrapFence = UUID.randomUUID();
        assertThat(claim(now, bootstrapFence, "chat-worker-bootstrap"))
                .isTrue();
        transaction.execute(status -> retentionTransactions.purgeAndSucceed(
                now, 100, UUID.randomUUID(),
                bootstrapFence, "chat-worker-bootstrap"));
        UUID staleFence = UUID.randomUUID();
        UUID winnerFence = UUID.randomUUID();
        assertThat(claim(now.minusMinutes(2), staleFence, "chat-worker-a")).isTrue();
        assertThat(serviceAt(now, "chat-worker-b").ready()).isFalse();
        assertThat(claim(now, winnerFence, "chat-worker-b")).isTrue();

        assertThatThrownBy(() -> transaction.execute(status -> repository.purgeExpired(
                now, 100, UUID.randomUUID(), staleFence, "chat-worker-a")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                repository.markSuccess(
                        now, staleFence, "chat-worker-a", false)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                repository.markFailure(
                        now, staleFence, "chat-worker-a", "STALE_WORKER")))
                .isInstanceOf(IllegalStateException.class);

        MeetingChatRetentionRepository.Health health = repository.health().orElseThrow();
        assertThat(health.activeFence()).isEqualTo(winnerFence);
        assertThat(health.activeWorkerId()).isEqualTo("chat-worker-b");
        assertThat(health.lastFailureCode()).isEqualTo("RETENTION_LEASE_EXPIRED");
    }

    @Test
    void auditFailureRollsBackPlaintextPurgeEvidenceAndTerminalSuccess() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID meetingId = endedMeeting(1);
        String sensitive = "Board-only restructuring detail";
        UUID messageId = insertMessage(1, meetingId, now.minusDays(1), sensitive);
        UUID fence = UUID.randomUUID();
        assertThat(claim(now, fence, "chat-worker-a")).isTrue();
        doThrow(new IllegalStateException("audit unavailable")).when(retentionAudit).purged(
                anyLong(), any(), any(), any(), any(), anyString());

        assertThatThrownBy(() -> transaction.execute(status ->
                retentionTransactions.purgeAndSucceed(
                        now.plusSeconds(1), 100, UUID.randomUUID(),
                        fence, "chat-worker-a")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(messageText(messageId)).isEqualTo(sensitive);
        assertThat(jdbc.queryForObject("""
                SELECT message_state FROM vm_meeting_chat_messages WHERE message_id = ?
                """, String.class, messageId)).isEqualTo("ACTIVE");
        assertThat(count("vm_meeting_chat_retention_evidence")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT last_success_at IS NULL AND active_fence = ?
                  FROM vm_meeting_chat_retention_health
                 WHERE health_key = 'CHAT_RETENTION'
                """, Boolean.class, fence)).isTrue();

        transaction.executeWithoutResult(status -> retentionTransactions.fail(
                now.plusSeconds(2), fence, "chat-worker-a"));
        assertThat(serviceAt(now.plusSeconds(3), "chat-worker-a").ready()).isFalse();
    }

    @Test
    void overdueBacklogBlocksReadinessUntilABoundedFollowupPollClearsIt() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID meetingId = endedMeeting(1);
        insertMessage(1, meetingId, now.minusDays(2), "first overdue message");
        insertMessage(1, meetingId, now.minusDays(1), "second overdue message");
        UUID firstFence = UUID.randomUUID();
        assertThat(claim(now, firstFence, "chat-worker-a")).isTrue();
        MeetingChatRetentionRepository.PurgeResult first = transaction.execute(status ->
                retentionTransactions.purgeAndSucceed(
                        now, 1, UUID.randomUUID(), firstFence, "chat-worker-a"));

        assertThat(first.deletedCount()).isOne();
        assertThat(first.overdueRemaining()).isTrue();
        assertThat(serviceAt(now.plusSeconds(1), "chat-worker-a").ready()).isFalse();

        UUID secondFence = UUID.randomUUID();
        assertThat(claim(now.plusSeconds(2), secondFence, "chat-worker-a")).isTrue();
        MeetingChatRetentionRepository.PurgeResult second = transaction.execute(status ->
                retentionTransactions.purgeAndSucceed(
                        now.plusSeconds(2), 100, UUID.randomUUID(),
                        secondFence, "chat-worker-a"));
        assertThat(second.deletedCount()).isOne();
        assertThat(second.overdueRemaining()).isFalse();
        assertThat(serviceAt(now.plusSeconds(3), "chat-worker-a").ready()).isTrue();
    }

    private boolean concurrentClaim(
            CountDownLatch ready,
            CountDownLatch start,
            OffsetDateTime now,
            UUID fence,
            String workerId) throws InterruptedException {
        ready.countDown();
        start.await();
        return claim(now, fence, workerId);
    }

    private boolean claim(OffsetDateTime now, UUID fence, String workerId) {
        return Boolean.TRUE.equals(transaction.execute(status ->
                retentionTransactions.attempt(
                        now, now.plusMinutes(1), fence, workerId)));
    }

    private MeetingChatRetentionService serviceAt(OffsetDateTime now, String workerId) {
        MeetingChatRetentionProperties properties = new MeetingChatRetentionProperties();
        properties.setEnabled(true);
        properties.setBatchSize(100);
        properties.setPollDelay(Duration.ofMinutes(5));
        properties.setLeaseDuration(Duration.ofMinutes(1));
        properties.setWorkerId(workerId);
        return new MeetingChatRetentionService(
                repository, retentionTransactions, properties,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    private UUID endedMeeting(long tenantId) {
        return jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = ? AND lifecycle_state = 'ENDED'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class, tenantId);
    }

    private UUID liveMeeting() {
        return jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE tenant_id = 1 AND lifecycle_state = 'LIVE'
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
    }

    private UUID createTenantTwoEndedMeeting(OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO vm_tenant_policies (tenant_id, created_by, updated_by)
                VALUES (2, 202, 202)
                """);
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address,
                    display_name, lifecycle_state)
                VALUES (2, 202, ?, 'tenant.two@example.test', 'Tenant Two', 'ACTIVE')
                """, personId);
        UUID meetingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meetings (
                    meeting_id, tenant_id, title, lifecycle_state, access_scope,
                    join_code, time_zone, provider, room_name, organizer_user_id,
                    organizer_person_public_id, organizer_name, started_at,
                    ended_at, ended_by, media_incarnation, media_access_state,
                    created_by, updated_by)
                VALUES (?, 2, 'Tenant two ended meeting', 'ENDED', 'INVITED',
                        '2BCD3FGH4JKL', 'Asia/Seoul', 'LIVEKIT', ?, 202, ?,
                        'Tenant Two', ?, ?, 202, ?, 'ENDED', 202, 202)
                """, meetingId, "retention-" + meetingId, personId,
                now.minusDays(2), now.minusDays(2).plusHours(1), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, user_id, person_public_id,
                    email_address, display_name, participant_role, attendance_state,
                    can_self_unmute, created_by, updated_by)
                VALUES (?, 2, ?, 202, ?, 'tenant.two@example.test', 'Tenant Two',
                        'ORGANIZER', 'INVITED', TRUE, 202, 202)
                """, UUID.randomUUID(), meetingId, personId);
        return meetingId;
    }

    private UUID insertMessage(
            long tenantId,
            UUID meetingId,
            OffsetDateTime retentionUntil,
            String text) {
        UUID messageId = UUID.randomUUID();
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(created_sequence), 0) + 1
                  FROM vm_meeting_chat_messages
                 WHERE tenant_id = ? AND meeting_id = ?
                """, Long.class, tenantId, meetingId);
        jdbc.update("""
                INSERT INTO vm_meeting_chat_messages (
                    message_id, tenant_id, meeting_id, participant_id, sender_user_id,
                    sender_person_public_id, sender_display_name, sender_role,
                    created_sequence, last_sequence, message_text, retention_until,
                    created_at, updated_at)
                SELECT ?, participant.tenant_id, participant.meeting_id,
                       participant.participant_id, participant.user_id,
                       participant.person_public_id, participant.display_name,
                       participant.participant_role, ?, ?, ?, ?, ?, ?
                  FROM vm_meeting_participants participant
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                 ORDER BY participant.participant_id LIMIT 1
                """, messageId, sequence, sequence, text, retentionUntil,
                retentionUntil.minusHours(1), retentionUntil.minusHours(1),
                tenantId, meetingId);
        return messageId;
    }

    private void insertCommand(
            long tenantId,
            UUID meetingId,
            UUID messageId,
            String commandType,
            String requestHash) {
        Long actorId = jdbc.queryForObject("""
                SELECT sender_user_id FROM vm_meeting_chat_messages WHERE message_id = ?
                """, Long.class, messageId);
        jdbc.update("""
                INSERT INTO vm_meeting_collaboration_commands (
                    tenant_id, meeting_id, actor_user_id, command_type,
                    idempotency_key, request_hash, result_resource_id,
                    result_sequence, result_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
                """, tenantId, meetingId, actorId, commandType,
                "retention:" + messageId, requestHash, messageId);
    }

    private void assertPurged(UUID messageId, long tenantId, UUID meetingId) {
        assertThat(jdbc.queryForObject("""
                SELECT message_state = 'DELETED'
                       AND message_text IS NULL
                       AND deletion_reason = 'RETENTION_EXPIRED'
                       AND deleted_by = 0
                  FROM vm_meeting_chat_messages
                 WHERE tenant_id = ? AND meeting_id = ? AND message_id = ?
                """, Boolean.class, tenantId, meetingId, messageId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_chat_retention_evidence
                 WHERE tenant_id = ? AND meeting_id = ? AND message_id = ?
                """, Integer.class, tenantId, meetingId, messageId)).isOne();
    }

    private String messageText(UUID messageId) {
        return jdbc.queryForObject("""
                SELECT message_text FROM vm_meeting_chat_messages WHERE message_id = ?
                """, String.class, messageId);
    }
}
