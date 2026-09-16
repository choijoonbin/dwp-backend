package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingServiceTest {

    @Mock
    private MessagingQueryRepository queries;
    @Mock
    private MessagingCommandRepository commands;
    @Mock
    private MessagingMessageQueryRepository messageQueries;
    @Mock
    private MessagingInteractionCommandRepository interactions;
    @Mock
    private MessagingEventRecorder events;

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void authorCanEditWithExpectedVersion() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(java.util.Optional.of(access(messageId, conversationId, 100, null, 3, "MEMBER")));
        when(queries.policy(1)).thenReturn(policy(true, true));
        when(interactions.editMessage(1, 100, conversationId, messageId, "updated", 3)).thenReturn(1);
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(java.util.Optional.of(message(messageId, conversationId, 4, null)));

        MessagingDtos.MessageSummary result = service().updateMessage(
                conversationId, messageId, new MessagingDtos.UpdateMessageRequest("updated", 3), "corr-edit");

        assertThat(result.version()).isEqualTo(4);
        verify(interactions).touchConversation(1, 100, conversationId);
        verify(events).conversationEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("messaging.message.updated"),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(messageId),
                anyMap());
    }

    @Test
    void moderatorCannotRewriteAnotherAuthorsMessage() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(java.util.Optional.of(access(messageId, conversationId, 200, null, 0, "MODERATOR")));

        assertThatThrownBy(() -> service().updateMessage(
                conversationId, messageId, new MessagingDtos.UpdateMessageRequest("rewrite", 0), null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(interactions, never()).editMessage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void moderatorCanSoftDeleteAnotherAuthorsMessage() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(java.util.Optional.of(access(messageId, conversationId, 200, null, 2, "MODERATOR")));
        when(queries.policy(1)).thenReturn(policy(true, true));
        when(interactions.softDeleteMessage(1, conversationId, messageId, 2)).thenReturn(1);
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(java.util.Optional.of(message(messageId, conversationId, 3, OffsetDateTime.now())));

        MessagingDtos.MessageSummary result = service().deleteMessage(
                conversationId, messageId, 2, "corr-delete");

        assertThat(result.deletedAt()).isNotNull();
        verify(events).conversationEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("messaging.message.deleted"),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(messageId),
                anyMap());
    }

    @Test
    void staleMessageVersionIsRejectedBeforeMutation() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(java.util.Optional.of(access(messageId, conversationId, 100, null, 7, "MEMBER")));
        when(queries.policy(1)).thenReturn(policy(true, true));

        assertThatThrownBy(() -> service().updateMessage(
                conversationId, messageId, new MessagingDtos.UpdateMessageRequest("stale", 6), null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(interactions, never()).editMessage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void replyCannotReferenceMessageOutsideVisibleConversation() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID foreignMessageId = UUID.randomUUID();
        allowConversation(conversationId);
        allowSending();
        when(messageQueries.access(1, conversationId, 100, foreignMessageId))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest("reply", UUID.randomUUID(), foreignMessageId),
                null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(commands, never()).insertMessage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void idempotencyConflictIsReportedBeforeMutableReplyValidation() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID changedReply = UUID.randomUUID();
        allowConversation(conversationId);
        when(commands.replayMessage(
                1, 100, conversationId, key, "reply", changedReply, List.of(), List.of()))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT, "Idempotency key payload changed."));

        assertThatThrownBy(() -> service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest("reply", key, changedReply),
                null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(messageQueries, never()).access(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nestedReplyIsRejectedToKeepThreadRootStable() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        allowConversation(conversationId);
        allowSending();
        when(messageQueries.access(1, conversationId, 100, replyId))
                .thenReturn(java.util.Optional.of(
                        access(replyId, conversationId, 200, UUID.randomUUID(), 0, "MEMBER")));

        assertThatThrownBy(() -> service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest("nested", UUID.randomUUID(), replyId),
                null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void messageHistoryUsesAValidatedConversationScopedCursor() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        allowConversation(conversationId);
        MessagingDtos.MessagePage page = new MessagingDtos.MessagePage(
                List.of(message(UUID.randomUUID(), conversationId, 0, null)), true, 87L);
        when(messageQueries.messagePage(1, conversationId, 100, 120L, 100))
                .thenReturn(page);

        MessagingDtos.MessagePage result = service().messages(conversationId, 120L, 500);

        assertThat(result).isSameAs(page);
        verify(messageQueries).messagePage(1, conversationId, 100, 120L, 100);
    }

    @Test
    void exactMessageReturnsAnAuthorizedReplyThatIsAbsentFromRootHistory() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID rootMessageId = UUID.randomUUID();
        allowConversation(conversationId);
        MessagingMessageAccess access = access(
                messageId, conversationId, 200, rootMessageId, 3, "MEMBER");
        MessagingDtos.MessageSummary reply = new MessagingDtos.MessageSummary(
                messageId, conversationId, 42, 200, UUID.randomUUID(), "Test User",
                "exact reply", "TEXT", "USER", rootMessageId, null, null,
                OffsetDateTime.parse("2026-09-04T09:12:00+09:00"), 3,
                List.of(), 0, null);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(access));
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(reply));

        MessagingDtos.MessageSummary result = service().message(conversationId, messageId);

        assertThat(result).isSameAs(reply);
        assertThat(result.replyToMessageId()).isEqualTo(rootMessageId);
        verify(messageQueries).access(1, conversationId, 100, messageId);
        verify(messageQueries).message(1, conversationId, 100, messageId);
    }

    @Test
    void exactMessageFailsClosedBeforeLoadingContentWhenMessageIsNotVisible() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().message(conversationId, messageId))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(messageQueries, never()).message(1, conversationId, 100, messageId);
    }

    @Test
    void workSourceReturnsCurrentEditedMessageFromTheExactAuthorizedConversation() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-04T09:12:00+09:00");
        OffsetDateTime editedAt = receivedAt.plusMinutes(5);
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(access(messageId, conversationId, 200, null, 7, "MEMBER")));
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(new MessagingDtos.MessageSummary(
                        messageId, conversationId, 14, 200, UUID.randomUUID(), "김채원 책임",
                        "  이번 주  공지 초안을\n정리해 주세요  ", "TEXT", "USER", null,
                        editedAt, null, receivedAt, 7, List.of(), 0, null)));

        MessagingDtos.WorkSourceMessage result = service().workSource(conversationId, messageId);

        assertThat(result.conversationId()).isEqualTo(conversationId);
        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.channelName()).isEqualTo("Test");
        assertThat(result.senderName()).isEqualTo("김채원 책임");
        assertThat(result.receivedAt()).isEqualTo(receivedAt);
        assertThat(result.editedAt()).isEqualTo(editedAt);
        assertThat(result.excerpt()).isEqualTo("이번 주 공지 초안을 정리해 주세요");
        assertThat(result.version()).isEqualTo(7);
    }

    @Test
    void workSourceFailsClosedAfterDeleteMembershipLossOrTenantMismatch() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        OffsetDateTime deletedAt = OffsetDateTime.now();
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(new MessagingMessageAccess(
                        messageId, conversationId, 1, 200, null, deletedAt, 3, "MEMBER")));
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(message(messageId, conversationId, 3, deletedAt)));
        assertThatThrownBy(() -> service().workSource(conversationId, messageId))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));

        UUID revokedConversation = UUID.randomUUID();
        when(queries.conversation(1, 100, revokedConversation)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().workSource(revokedConversation, messageId))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
        verify(messageQueries, never()).access(1, revokedConversation, 100, messageId);
    }

    @Test
    void invisibleConversationHistoryIsReportedAsNotFoundWithoutQueryingMessages() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        when(queries.conversation(1, 100, conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().messages(conversationId, null, 50))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));

        verify(messageQueries, never()).messagePage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nonPositiveMessageCursorIsRejectedAfterConversationVisibilityCheck() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        allowConversation(conversationId);

        assertThatThrownBy(() -> service().messages(conversationId, 0L, 50))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(messageQueries, never()).messagePage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void directConversationCommandReturnsOnlyTheConversationSummary() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        MessagingDtos.ConversationSummary summary = conversation(conversationId);
        when(queries.policy(1)).thenReturn(policy(true, true));
        when(queries.person(1, 200)).thenReturn(Optional.of(new MessagingDtos.PersonSummary(
                200, UUID.randomUUID(), "person@example.com", "Person", null, null, "ONLINE")));
        when(commands.directConversation(1, 100, 200)).thenReturn(conversationId);
        when(queries.conversation(1, 100, conversationId)).thenReturn(Optional.of(summary));

        MessagingDtos.ConversationSummary result = service().createDirectConversation(
                new MessagingDtos.DirectConversationRequest(200L), "corr-direct");

        assertThat(result).isSameAs(summary);
        verify(queries, never()).members(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(queries, never()).messages(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void sendReturnsOnlyTheCreatedMessageDelta() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        allowConversation(conversationId);
        allowSending();
        when(commands.replayMessage(
                1, 100, conversationId, idempotencyKey, "hello", null, List.of(), List.of()))
                .thenReturn(Optional.empty());
        when(commands.insertMessage(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(idempotencyKey),
                org.mockito.ArgumentMatchers.eq("Test User"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq("hello"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(List.of())))
                .thenReturn(new MessagingCommandRepository.MessageInsertResult(messageId, true, 12));
        MessagingDtos.MessageSummary delta = message(messageId, conversationId, 0, null);
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(delta));

        MessagingDtos.MessageSummary result = service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest("hello", idempotencyKey, null),
                "corr-send");

        assertThat(result).isSameAs(delta);
        verify(events).conversationEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("messaging.message.created"),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(messageId),
                anyMap());
        verify(queries, never()).members(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(queries, never()).messages(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void idempotentSendReplayReturnsTheOriginalMessageDeltaWithoutAnotherEvent() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        allowConversation(conversationId);
        when(commands.replayMessage(
                1, 100, conversationId, idempotencyKey, "hello", null, List.of(), List.of()))
                .thenReturn(Optional.of(
                        new MessagingCommandRepository.MessageInsertResult(messageId, false, 7)));
        MessagingDtos.MessageSummary delta = message(messageId, conversationId, 0, null);
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(delta));

        MessagingDtos.MessageSummary result = service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest("hello", idempotencyKey, null),
                "corr-replay");

        assertThat(result).isSameAs(delta);
        verify(commands, never()).insertMessage(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(events, never()).conversationEvent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyMap());
    }

    @Test
    void reactionsReturnOnlyTheUpdatedMessageDelta() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(messageQueries.access(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(access(messageId, conversationId, 200, null, 0, "MEMBER")));
        when(commands.react(1, 100, messageId, "thumbs-up")).thenReturn(1);
        when(commands.removeReaction(1, 100, messageId, "thumbs-up")).thenReturn(1);
        MessagingDtos.MessageSummary added = message(messageId, conversationId, 0, null);
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(added));

        MessagingDtos.MessageSummary addResult = service().addReaction(
                conversationId, messageId, new MessagingDtos.ReactionRequest("thumbs-up"));
        MessagingDtos.MessageSummary removeResult = service().removeReaction(
                conversationId, messageId, "thumbs-up");

        assertThat(addResult).isSameAs(added);
        assertThat(removeResult).isSameAs(added);
        verify(events).conversationEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("messaging.reaction.added"),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(messageId),
                anyMap());
        verify(events).conversationEvent(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("messaging.reaction.removed"),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(messageId),
                anyMap());
        verify(queries, never()).members(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(queries, never()).messages(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void olderReadCursorRequestIsAnIdempotentNoOp() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID requestedMessageId = UUID.randomUUID();
        UUID currentMessageId = UUID.randomUUID();
        allowConversation(conversationId);
        when(commands.markRead(1, 100, conversationId, requestedMessageId))
                .thenReturn(Optional.of(new MessagingCommandRepository.ReadCursorState(
                        requestedMessageId, 4, currentMessageId, 9,
                        OffsetDateTime.parse("2026-08-19T01:00:00Z"), 3, false)));

        MessagingDtos.ReadCursorResponse result = service().markRead(
                conversationId, new MessagingDtos.ReadCursorRequest(requestedMessageId));

        assertThat(result).isEqualTo(new MessagingDtos.ReadCursorResponse(
                conversationId, currentMessageId, 9,
                OffsetDateTime.parse("2026-08-19T01:00:00Z"), 3));
        verify(events, never()).privateEvent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyMap());
        verify(queries, never()).members(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(queries, never()).messages(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void messageRequestFingerprintUsesNormalizedBodyAndConversationScope() {
        UUID conversationId = UUID.randomUUID();
        String first = MessagingCommandRepository.sendMessageRequestHash(
                conversationId,
                MessagingCommandRepository.normalizeBody("  line one\r\nline two  "),
                null);
        String replay = MessagingCommandRepository.sendMessageRequestHash(
                conversationId,
                MessagingCommandRepository.normalizeBody("line one\nline two"),
                null);
        String changedConversation = MessagingCommandRepository.sendMessageRequestHash(
                UUID.randomUUID(),
                MessagingCommandRepository.normalizeBody("line one\nline two"),
                null);

        assertThat(first).isEqualTo(replay).hasSize(64);
        assertThat(changedConversation).isNotEqualTo(first);
    }

    @Test
    void messageRequestFingerprintIncludesMentionRecipients() {
        UUID conversationId = UUID.randomUUID();
        String first = MessagingCommandRepository.sendMessageRequestHash(
                conversationId, "hello", null, List.of(), List.of(200L));
        String reordered = MessagingCommandRepository.sendMessageRequestHash(
                conversationId, "hello", null, List.of(), List.of(300L, 200L));
        String sameReordered = MessagingCommandRepository.sendMessageRequestHash(
                conversationId, "hello", null, List.of(), List.of(200L, 300L));

        assertThat(reordered).isEqualTo(sameReordered);
        assertThat(first).isNotEqualTo(reordered);
    }

    @Test
    void structuredMentionsAreValidatedAndPersistedWithTheMessage() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        allowConversation(conversationId);
        allowSending();
        when(commands.replayMessage(
                1, 100, conversationId, idempotencyKey, "@Person 확인 부탁드립니다", null,
                List.of(), List.of(200L))).thenReturn(Optional.empty());
        when(queries.members(1, conversationId)).thenReturn(List.of(
                member(100, "Test User", "MEMBER"), member(200, "Person", "MEMBER")));
        when(commands.insertMessage(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(idempotencyKey),
                org.mockito.ArgumentMatchers.eq("Test User"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq("@Person 확인 부탁드립니다"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(List.of(200L))))
                .thenReturn(new MessagingCommandRepository.MessageInsertResult(messageId, true, 15));
        when(messageQueries.message(1, conversationId, 100, messageId))
                .thenReturn(Optional.of(message(messageId, conversationId, 0, null)));

        service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest(
                        "@Person 확인 부탁드립니다", idempotencyKey, null,
                        List.of(), List.of(200L)),
                "corr-mention");

        verify(commands).insertMentions(
                1, conversationId, messageId,
                List.of(new MessagingDtos.MentionSummary(200, "Person", "USER")));
    }

    @Test
    void ordinaryMembersCannotNotifyEveryone() {
        MessagingRequestContext.set(subject(100));
        UUID conversationId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        allowConversation(conversationId);
        allowSending();
        when(commands.replayMessage(
                1, 100, conversationId, idempotencyKey, "@모두 확인", null,
                List.of(), List.of(200L, 300L, 400L))).thenReturn(Optional.empty());
        when(queries.members(1, conversationId)).thenReturn(List.of(
                member(100, "Test User", "MEMBER"),
                member(200, "Person A", "MEMBER"),
                member(300, "Person B", "MEMBER"),
                member(400, "Person C", "MEMBER")));

        assertThatThrownBy(() -> service().sendMessage(
                conversationId,
                new MessagingDtos.SendMessageRequest(
                        "@모두 확인", idempotencyKey, null,
                        List.of(), List.of(200L, 300L, 400L)),
                null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private MessagingService service() {
        return new MessagingService(queries, commands, messageQueries, interactions, events);
    }

    private void allowConversation(UUID conversationId) {
        when(queries.conversation(1, 100, conversationId))
                .thenReturn(java.util.Optional.of(conversation(conversationId)));
    }

    private void allowSending() {
        when(queries.policy(1)).thenReturn(policy(true, true));
    }

    private MessagingRequestContext.Subject subject(long userId) {
        return new MessagingRequestContext.Subject(
                userId, 1, UUID.randomUUID(), "Test User",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MESSAGING:UPDATE"), Set.of());
    }

    private MessagingDtos.ConversationSummary conversation(UUID conversationId) {
        return new MessagingDtos.ConversationSummary(
                conversationId, "channel:test", "CHANNEL", "Test", null,
                "PRIVATE", "INTERNAL", null, null, "ACTIVE", 2, 0,
                false, false, null, OffsetDateTime.now(), 0);
    }

    private MessagingDtos.MemberSummary member(long userId, String name, String role) {
        return new MessagingDtos.MemberSummary(
                userId, UUID.randomUUID(), name, name.toLowerCase().replace(' ', '.') + "@example.com",
                "Engineer", "DWP", "AVAILABLE", role, "DIRECT", "DEFAULT",
                false, false, null, 0, null, "SHARED");
    }

    private MessagingMessageAccess access(
            UUID messageId,
            UUID conversationId,
            long senderUserId,
            UUID replyTo,
            long version,
            String role) {
        return new MessagingMessageAccess(
                messageId, conversationId, 1, senderUserId, replyTo, null, version, role);
    }

    private MessagingDtos.MessageSummary message(
            UUID messageId, UUID conversationId, long version, OffsetDateTime deletedAt) {
        return new MessagingDtos.MessageSummary(
                messageId, conversationId, 1, 100, UUID.randomUUID(), "Test User",
                deletedAt == null ? "body" : "", "TEXT", "USER", null,
                null, deletedAt, OffsetDateTime.now(), version, List.of(), 0, null);
    }

    private MessagingDtos.TenantPolicy policy(boolean edit, boolean delete) {
        return new MessagingDtos.TenantPolicy(
                true, true, edit, delete, true, false, 1095, 100, 0);
    }
}
