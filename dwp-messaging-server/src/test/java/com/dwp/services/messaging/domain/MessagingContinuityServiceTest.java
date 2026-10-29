package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingContinuityServiceTest {

    @Mock
    private MessagingContinuityRepository continuity;
    @Mock
    private MessagingService messaging;
    @Mock
    private MessagingCommandRepository commands;

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void staleDraftVersionIsRejectedWithoutOverwritingTheServerDraft() {
        subject();
        UUID conversationId = UUID.randomUUID();
        when(continuity.updateDraft(1, 100, conversationId, null, "local", List.of(), 4))
                .thenReturn(0);

        assertThatThrownBy(() -> service().saveDraft(
                conversationId,
                null,
                new MessagingContinuityDtos.SaveDraftRequest("local", List.of(), 4)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(messaging).validateDraft(conversationId, null, "local", List.of());
    }

    @Test
    void unknownPublicMentionIdentityFailsClosedBeforeDraftValidationOrPersistence() {
        subject();
        UUID conversationId = UUID.randomUUID();
        UUID unknownPersonPublicId = UUID.randomUUID();
        when(continuity.activeMentionIdentities(1, List.of(unknownPersonPublicId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().saveDraft(
                conversationId,
                null,
                new MessagingContinuityDtos.SaveDraftRequest(
                        "hello", List.of(unknownPersonPublicId), 0)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(messaging, never()).validateDraft(
                conversationId, null, "hello", List.of());
        verify(continuity, never()).insertDraft(
                1, 100, conversationId, null, "hello", List.of());
    }

    @Test
    void scheduledAttachmentsFailClosedBeforeAnyDurableCommandIsCreated() {
        subject();
        UUID conversationId = UUID.randomUUID();
        var request = new MessagingContinuityDtos.ScheduleMessageRequest(
                "report", UUID.randomUUID(), null, List.of(UUID.randomUUID()), List.of(),
                OffsetDateTime.now().plusHours(1), "Asia/Seoul");

        assertThatThrownBy(() -> service().schedule(conversationId, request, null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(continuity, never()).insertScheduled(any());
    }

    @Test
    void scheduleStoresUtcInstantAndReplaysTheSameIdempotencyCommand() {
        subject();
        UUID conversationId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        UUID firstPersonPublicId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID secondPersonPublicId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        List<UUID> mentionedPersonPublicIds = List.of(firstPersonPublicId, secondPersonPublicId);
        OffsetDateTime requested = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(10);
        var captured = new AtomicReference<MessagingContinuityRepository.ScheduledCommand>();
        when(continuity.activeMentionIdentities(1, mentionedPersonPublicIds)).thenReturn(List.of(
                new MessagingContinuityRepository.MentionIdentity(firstPersonPublicId, 200),
                new MessagingContinuityRepository.MentionIdentity(secondPersonPublicId, 300)));
        when(continuity.insertScheduled(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return true;
        });
        when(continuity.scheduledByRequestKey(1, 100, requestKey)).thenAnswer(invocation -> {
            var command = captured.get();
            if (command == null) return Optional.empty();
            var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                    command.scheduledMessageId(), command.conversationId(), command.body(),
                    command.replyToMessageId(), mentionedPersonPublicIds, command.scheduledFor(),
                    command.displayTimeZone(), "PENDING", 0, null, null, null, null, 0,
                    OffsetDateTime.now(), OffsetDateTime.now(), "Conversation", null);
            return Optional.of(new MessagingContinuityRepository.ScheduledRecord(
                    response, command.tenantId(), command.senderUserId(),
                    command.mentionedUserIds(),
                    command.requestIdempotencyKey(), command.sendIdempotencyKey(),
                    command.requestHash()));
        });

        var result = service().schedule(
                conversationId,
                new MessagingContinuityDtos.ScheduleMessageRequest(
                        " report ", requestKey, null, List.of(), mentionedPersonPublicIds,
                        requested, "Asia/Seoul"),
                "corr-schedule");

        assertThat(result.scheduledFor()).isEqualTo(requested.withOffsetSameInstant(ZoneOffset.UTC));
        assertThat(result.mentionedPersonPublicIds())
                .containsExactly(firstPersonPublicId, secondPersonPublicId);
        assertThat(captured.get().mentionedUserIds()).containsExactly(200L, 300L);
        assertThat(captured.get().sendIdempotencyKey()).isNotEqualTo(requestKey);
        verify(messaging).validateScheduledMessage(
                conversationId, "report", null, List.of(200L, 300L));
    }

    @Test
    void scheduleReplayReturnsTheStoredResponseAfterTimeAndAuthorityStateChanged() {
        subject();
        OffsetDateTime firstRequestTime = OffsetDateTime.parse("2026-10-28T10:00:00Z");
        OffsetDateTime scheduledFor = firstRequestTime.plusMinutes(30);
        UUID conversationId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        UUID mentionedPersonPublicId = UUID.randomUUID();
        var request = new MessagingContinuityDtos.ScheduleMessageRequest(
                " report ", requestKey, null, List.of(), List.of(mentionedPersonPublicId),
                scheduledFor, "Asia/Seoul");
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                UUID.randomUUID(), conversationId, "report", null,
                List.of(mentionedPersonPublicId), scheduledFor, "Asia/Seoul", "PENDING", 0,
                null, null, null, null, 0, firstRequestTime, firstRequestTime,
                null, null);
        String requestHash = MessagingContinuityService.scheduleHash(
                conversationId, "report", null, List.of(mentionedPersonPublicId),
                scheduledFor, "Asia/Seoul");
        when(continuity.scheduledByRequestKey(1, 100, requestKey)).thenReturn(Optional.of(
                new MessagingContinuityRepository.ScheduledRecord(
                        response, 1, 100, List.of(200L), requestKey,
                        UUID.randomUUID(), requestHash)));

        var replayed = serviceAt(firstRequestTime.plusHours(1))
                .schedule(conversationId, request, "corr-replay");

        assertThat(replayed).isSameAs(response);
        verify(continuity, never()).activeMentionIdentities(anyLong(), any());
        verify(messaging, never()).validateScheduledMessage(any(), any(), any(), any());
        verify(continuity, never()).insertScheduled(any());
        verify(commands, never()).audit(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void scheduleReplayWithDifferentPayloadConflictsBeforeMutableValidation() {
        subject();
        OffsetDateTime scheduledFor = OffsetDateTime.parse("2026-10-28T10:30:00Z");
        UUID conversationId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        UUID mentionedPersonPublicId = UUID.randomUUID();
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                UUID.randomUUID(), conversationId, "original", null,
                List.of(mentionedPersonPublicId), scheduledFor, "UTC", "PENDING", 0,
                null, null, null, null, 0, scheduledFor.minusHours(1),
                scheduledFor.minusHours(1), null, null);
        String originalHash = MessagingContinuityService.scheduleHash(
                conversationId, "original", null, List.of(mentionedPersonPublicId),
                scheduledFor, "UTC");
        when(continuity.scheduledByRequestKey(1, 100, requestKey)).thenReturn(Optional.of(
                new MessagingContinuityRepository.ScheduledRecord(
                        response, 1, 100, List.of(200L), requestKey,
                        UUID.randomUUID(), originalHash)));

        assertThatThrownBy(() -> serviceAt(scheduledFor.plusHours(1)).schedule(
                conversationId,
                new MessagingContinuityDtos.ScheduleMessageRequest(
                        "changed", requestKey, null, List.of(),
                        List.of(mentionedPersonPublicId), scheduledFor, "UTC"),
                "corr-conflict"))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(continuity, never()).activeMentionIdentities(anyLong(), any());
        verify(messaging, never()).validateScheduledMessage(any(), any(), any(), any());
        verify(continuity, never()).insertScheduled(any());
    }

    @Test
    void scheduleWithANewKeyStillRequiresAnActiveMentionIdentity() {
        subject();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-28T10:00:00Z");
        UUID conversationId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        UUID unavailablePersonPublicId = UUID.randomUUID();
        when(continuity.scheduledByRequestKey(1, 100, requestKey)).thenReturn(Optional.empty());
        when(continuity.activeMentionIdentities(1, List.of(unavailablePersonPublicId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> serviceAt(now).schedule(
                conversationId,
                new MessagingContinuityDtos.ScheduleMessageRequest(
                        "report", requestKey, null, List.of(),
                        List.of(unavailablePersonPublicId), now.plusMinutes(30), "UTC"),
                "corr-new"))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(messaging, never()).validateScheduledMessage(any(), any(), any(), any());
        verify(continuity, never()).insertScheduled(any());
    }

    @Test
    void scheduleRejectsReplayAfterRetentionReplacedTheContentDerivedHash() {
        subject();
        UUID conversationId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        OffsetDateTime scheduledFor = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                UUID.randomUUID(), conversationId, "", null, List.of(), scheduledFor,
                "UTC", "SENT", 1, null, null, null, null, 1,
                scheduledFor.minusDays(2), scheduledFor.minusDays(1),
                "Conversation", scheduledFor.minusDays(1));
        when(continuity.scheduledByRequestKey(1, 100, requestKey)).thenReturn(Optional.of(
                new MessagingContinuityRepository.ScheduledRecord(
                        response, 1, 100, List.of(), requestKey,
                        UUID.randomUUID(), "f".repeat(64))));

        assertThatThrownBy(() -> service().schedule(
                conversationId,
                new MessagingContinuityDtos.ScheduleMessageRequest(
                        "report", requestKey, null, List.of(), List.of(), scheduledFor, "UTC"),
                "corr-retained-replay"))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void scheduledListUsesAStatusBoundCursorAndCurrentConversationIdentity() {
        subject();
        OffsetDateTime now = OffsetDateTime.parse("2026-10-28T10:00:00Z");
        var first = scheduledItem(now, "Risk room");
        var second = scheduledItem(now.minusMinutes(1), "Operations");
        var lookahead = scheduledItem(now.minusMinutes(2), null);
        when(continuity.scheduled(1, 100, "PENDING", null, 3))
                .thenReturn(List.of(first, second, lookahead));

        MessagingContinuityDtos.ScheduledMessagePage page =
                service().scheduled("pending", null, 2);

        assertThat(page.items()).containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.items().getFirst().conversationName()).isEqualTo("Risk room");
        assertThatThrownBy(() -> service().scheduled("FAILED", page.nextCursor(), 2))
                .isInstanceOf(BaseException.class);
    }

    private MessagingContinuityService service() {
        return new MessagingContinuityService(continuity, messaging, commands);
    }

    private MessagingContinuityService serviceAt(OffsetDateTime now) {
        return new MessagingContinuityService(
                continuity, messaging, commands, Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    private MessagingContinuityDtos.ScheduledMessageResponse scheduledItem(
            OffsetDateTime createdAt, String conversationName) {
        return new MessagingContinuityDtos.ScheduledMessageResponse(
                UUID.randomUUID(), UUID.randomUUID(), "body", null, List.of(),
                createdAt.plusDays(1), "UTC", "PENDING", 0,
                null, null, null, null, 0, createdAt, createdAt,
                conversationName, null);
    }

    private void subject() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100, 1, UUID.randomUUID(), "Test User", Set.of(), Set.of(), Set.of()));
    }
}
