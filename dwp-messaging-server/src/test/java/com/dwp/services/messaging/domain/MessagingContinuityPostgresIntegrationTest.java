package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingContinuityPostgresIntegrationTest {

    private static final long TENANT = 96_419;
    private static final long OTHER_TENANT = 96_420;
    private static JdbcTemplate jdbc;
    private MessagingContinuityRepository repository;
    private UUID conversationId;
    private UUID mentionedPersonPublicId;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault(
                        "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault(
                        "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)",
                TENANT, OTHER_TENANT);
        conversationId = UUID.randomUUID();
        mentionedPersonPublicId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                        tenant_id, user_id, person_public_id, email_address, display_name)
                    VALUES
                        (?, 100, ?, 'continuity@example.com', 'Continuity User'),
                        (?, 200, ?, 'mentioned@example.com', 'Mentioned User'),
                        (?, 300, ?, 'second-mentioned@example.com', 'Second Mentioned User')
                """, TENANT, UUID.randomUUID(), TENANT, mentionedPersonPublicId,
                TENANT, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification)
                VALUES (?, ?, ?, 'GROUP', 'Continuity', 'PRIVATE', 'INTERNAL')
                """, conversationId, TENANT, "continuity:" + conversationId);
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, lifecycle_state)
                VALUES (?, ?, 100, 'ACTIVE')
                """, TENANT, conversationId);
        repository = new MessagingContinuityRepository(jdbc);
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void draftScopeIsUniqueAndVersionUpdatesAreCompareAndSwap() {
        assertThat(repository.insertDraft(
                TENANT, 100, conversationId, null, "first", List.of(200L))).isTrue();
        var created = repository.draft(TENANT, 100, conversationId, null).orElseThrow();

        assertThat(created.version()).isEqualTo(1);
        assertThat(repository.insertDraft(
                TENANT, 100, conversationId, null, "racing", List.of())).isFalse();
        assertThat(repository.updateDraft(
                TENANT, 100, conversationId, null, "stale", List.of(), 0)).isZero();
        assertThat(repository.updateDraft(
                TENANT, 100, conversationId, null, "second", List.of(200L), 1)).isOne();
        var updated = repository.draft(TENANT, 100, conversationId, null).orElseThrow();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.body()).isEqualTo("second");
        assertThat(updated.mentionedPersonPublicIds()).containsExactly(mentionedPersonPublicId);
    }

    @Test
    void publicMentionLookupIsTenantScopedAndActiveOnly() {
        UUID crossTenantPersonPublicId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address, display_name)
                VALUES (?, 200, ?, 'other-tenant@example.com', 'Other Tenant User')
                """, OTHER_TENANT, crossTenantPersonPublicId);

        assertThat(repository.activeMentionIdentities(
                TENANT, List.of(mentionedPersonPublicId)))
                .containsExactly(new MessagingContinuityRepository.MentionIdentity(
                        mentionedPersonPublicId, 200));
        assertThat(repository.activeMentionIdentities(
                TENANT, List.of(crossTenantPersonPublicId))).isEmpty();
        jdbc.update("""
                UPDATE msg_people_snapshot SET lifecycle_state = 'INACTIVE'
                 WHERE tenant_id = ? AND person_public_id = ?
                """, TENANT, mentionedPersonPublicId);
        assertThat(repository.activeMentionIdentities(
                TENANT, List.of(mentionedPersonPublicId))).isEmpty();
    }

    @Test
    void dueCommandsAreClaimedOnceAndCancellationRequiresTheCurrentVersion() {
        var due = command(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        var future = command(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(repository.insertScheduled(due)).isTrue();
        assertThat(repository.insertScheduled(future)).isTrue();

        var claimed = repository.claimDue(10);
        assertThat(claimed).extracting(item -> item.response().scheduledMessageId())
                .containsExactly(due.scheduledMessageId());
        assertThat(claimed.getFirst().response().mentionedPersonPublicIds())
                .containsExactly(mentionedPersonPublicId);
        assertThat(claimed.getFirst().mentionedUserIds()).containsExactly(200L);
        assertThat(repository.claimDue(10)).isEmpty();
        assertThat(repository.cancel(TENANT, 100, future.scheduledMessageId(), 1)).isEmpty();
        var cancelled = repository.cancel(
                TENANT, 100, future.scheduledMessageId(), 0).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.version()).isEqualTo(1);
    }

    @Test
    void scheduledListUsesStableCursorAndOnlyProjectsCurrentlyAuthorizedConversationName() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        var newest = command(base.plusMinutes(3));
        var middle = command(base.plusMinutes(2));
        var oldest = command(base.plusMinutes(1));
        repository.insertScheduled(newest);
        repository.insertScheduled(middle);
        repository.insertScheduled(oldest);
        jdbc.update("UPDATE msg_scheduled_messages SET created_at = ? WHERE scheduled_message_id = ?",
                base.plusSeconds(3), newest.scheduledMessageId());
        jdbc.update("UPDATE msg_scheduled_messages SET created_at = ? WHERE scheduled_message_id = ?",
                base.plusSeconds(2), middle.scheduledMessageId());
        jdbc.update("UPDATE msg_scheduled_messages SET created_at = ? WHERE scheduled_message_id = ?",
                base.plusSeconds(1), oldest.scheduledMessageId());

        var first = repository.scheduled(TENANT, 100, null, null, 2);
        var cursor = new MessagingScheduledCursor.Cursor(
                first.getLast().createdAt(), first.getLast().scheduledMessageId());
        var second = repository.scheduled(TENANT, 100, null, cursor, 2);

        assertThat(first).extracting(MessagingContinuityDtos.ScheduledMessageResponse::scheduledMessageId)
                .containsExactly(newest.scheduledMessageId(), middle.scheduledMessageId());
        assertThat(first).extracting(MessagingContinuityDtos.ScheduledMessageResponse::conversationName)
                .containsOnly("Continuity");
        assertThat(second).extracting(MessagingContinuityDtos.ScheduledMessageResponse::scheduledMessageId)
                .containsExactly(oldest.scheduledMessageId());

        jdbc.update("""
                UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED'
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = 100
                """, TENANT, conversationId);
        assertThat(repository.scheduled(TENANT, 100, newest.scheduledMessageId()))
                .get().extracting(MessagingContinuityDtos.ScheduledMessageResponse::conversationName)
                .isNull();
    }

    @Test
    void idempotentReplaySurvivesElapsedTimeAndRevokedIdentityWhileMismatchConflicts() {
        OffsetDateTime initialNow = OffsetDateTime.parse("2026-10-28T10:00:00Z");
        OffsetDateTime scheduledFor = initialNow.plusMinutes(30);
        UUID requestKey = UUID.randomUUID();
        var request = new MessagingContinuityDtos.ScheduleMessageRequest(
                " scheduled report ", requestKey, null, List.of(),
                List.of(mentionedPersonPublicId), scheduledFor, "Asia/Seoul");
        MessagingService messaging = mock(MessagingService.class);
        MessagingCommandRepository commands = mock(MessagingCommandRepository.class);
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100, TENANT, UUID.randomUUID(), "Continuity User",
                Set.of(), Set.of(), Set.of()));
        var initialService = new MessagingContinuityService(
                repository, messaging, commands,
                Clock.fixed(initialNow.toInstant(), ZoneOffset.UTC));

        var created = initialService.schedule(conversationId, request, "corr-created");

        jdbc.update("""
                UPDATE msg_people_snapshot SET lifecycle_state = 'INACTIVE'
                 WHERE tenant_id = ? AND person_public_id = ?
                """, TENANT, mentionedPersonPublicId);
        jdbc.update("""
                UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED'
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = 100
                """, TENANT, conversationId);
        var replayService = new MessagingContinuityService(
                repository, messaging, commands,
                Clock.fixed(scheduledFor.plusHours(1).toInstant(), ZoneOffset.UTC));

        var replayed = replayService.schedule(conversationId, request, "corr-replayed");

        assertThat(replayed.scheduledMessageId()).isEqualTo(created.scheduledMessageId());
        assertThat(replayed.mentionedPersonPublicIds())
                .containsExactly(mentionedPersonPublicId);
        assertThat(replayed.conversationName()).isNull();
        verify(messaging).validateScheduledMessage(
                conversationId, "scheduled report", null, List.of(200L));

        assertThatThrownBy(() -> replayService.schedule(
                conversationId,
                new MessagingContinuityDtos.ScheduleMessageRequest(
                        "changed report", requestKey, null, List.of(),
                        List.of(mentionedPersonPublicId), scheduledFor, "Asia/Seoul"),
                "corr-mismatch"))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        UUID newRequestKey = UUID.randomUUID();
        assertThatThrownBy(() -> replayService.schedule(
                conversationId,
                new MessagingContinuityDtos.ScheduleMessageRequest(
                        "new request", newRequestKey, null, List.of(),
                        List.of(mentionedPersonPublicId), scheduledFor.plusHours(2), "UTC"),
                "corr-new-key"))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM msg_scheduled_messages
                 WHERE tenant_id = ? AND sender_user_id = 100
                """, Integer.class, TENANT)).isOne();
        verify(commands).audit(
                eq(TENANT), eq(100L), eq("messaging.message.scheduled"),
                eq("MSG_SCHEDULED_MESSAGE"), eq(created.scheduledMessageId().toString()),
                eq("corr-created"), anyMap());
        verifyNoMoreInteractions(commands);
    }

    private MessagingContinuityRepository.ScheduledCommand command(OffsetDateTime scheduledFor) {
        return new MessagingContinuityRepository.ScheduledCommand(
                UUID.randomUUID(), TENANT, 100, conversationId, "scheduled", null, List.of(200L),
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), scheduledFor, "UTC");
    }
}
