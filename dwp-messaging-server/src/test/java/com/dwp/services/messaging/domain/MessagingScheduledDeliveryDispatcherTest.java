package com.dwp.services.messaging.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingScheduledDeliveryDispatcherTest {

    @Mock
    private MessagingContinuityRepository continuity;
    @Mock
    private MessagingService messaging;

    @Test
    void crashRecoveryReconcilesTheCommittedIdempotencyResultBeforeAuthorityChanges() {
        UUID scheduledId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID sendKey = UUID.randomUUID();
        UUID deliveredMessageId = UUID.randomUUID();
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                scheduledId, conversationId, "already delivered", null, List.of(),
                OffsetDateTime.now(), "UTC", "PROCESSING", 2,
                null, null, null, null, 3, OffsetDateTime.now(), OffsetDateTime.now(),
                "Conversation", null);
        var record = new MessagingContinuityRepository.ScheduledRecord(
                response, 1, 100, List.of(), UUID.randomUUID(), sendKey, "a".repeat(64));
        when(continuity.deliveredMessage(1, 100, conversationId, sendKey))
                .thenReturn(Optional.of(deliveredMessageId));

        UUID result = new MessagingScheduledDeliveryDispatcher(continuity, messaging)
                .dispatch(record);

        assertThat(result).isEqualTo(deliveredMessageId);
        verify(continuity, never()).activePerson(1, 100);
        verify(messaging, never()).sendMessage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchUsesWorkerOnlyInternalMentionIdsWhileTheResponseKeepsPublicIdentities() {
        UUID scheduledId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID sendKey = UUID.randomUUID();
        UUID personPublicId = UUID.randomUUID();
        UUID deliveredMessageId = UUID.randomUUID();
        var response = new MessagingContinuityDtos.ScheduledMessageResponse(
                scheduledId, conversationId, "deliver later", null, List.of(personPublicId),
                OffsetDateTime.now(), "UTC", "PROCESSING", 1,
                null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now(),
                "Conversation", null);
        var record = new MessagingContinuityRepository.ScheduledRecord(
                response, 1, 100, List.of(200L), UUID.randomUUID(), sendKey, "a".repeat(64));
        when(continuity.deliveredMessage(1, 100, conversationId, sendKey))
                .thenReturn(Optional.empty());
        when(continuity.activePerson(1, 100)).thenReturn(Optional.of(
                new MessagingContinuityRepository.PersonIdentity(personPublicId, "Person")));
        MessagingDtos.MessageSummary delivered = mock(MessagingDtos.MessageSummary.class);
        when(delivered.messageId()).thenReturn(deliveredMessageId);
        when(messaging.sendMessage(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("scheduled:" + scheduledId)))
                .thenReturn(delivered);

        UUID result = new MessagingScheduledDeliveryDispatcher(continuity, messaging)
                .dispatch(record);

        ArgumentCaptor<MessagingDtos.SendMessageRequest> request =
                ArgumentCaptor.forClass(MessagingDtos.SendMessageRequest.class);
        verify(messaging).sendMessage(
                org.mockito.ArgumentMatchers.eq(conversationId),
                request.capture(),
                org.mockito.ArgumentMatchers.eq("scheduled:" + scheduledId));
        assertThat(result).isEqualTo(deliveredMessageId);
        assertThat(request.getValue().mentionedUserIds()).containsExactly(200L);
        assertThat(response.mentionedPersonPublicIds()).containsExactly(personPublicId);
    }
}
