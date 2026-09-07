package com.dwp.services.messaging.realtime;

import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingEventRecorderTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsDomainEventAppendOutsideTheOwningTransaction() {
        MessagingEventRecorder recorder = new MessagingEventRecorder(
                mock(MessagingRealtimeRepository.class), mock(MessagingRealtimePublisher.class));

        assertThatThrownBy(() -> recorder.conversationEvent(
                subject(), "messaging.message.created", UUID.randomUUID(), UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owning database transaction");
    }

    @Test
    void appendsAndSchedulesPublicationInsideTheOwningTransaction() {
        MessagingRealtimeRepository repository = mock(MessagingRealtimeRepository.class);
        MessagingRealtimePublisher publisher = mock(MessagingRealtimePublisher.class);
        MessagingEventRecorder recorder = new MessagingEventRecorder(repository, publisher);
        MessagingRequestContext.Subject subject = subject();
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MessagingRealtimeEvent event = new MessagingRealtimeEvent(
                1, UUID.randomUUID(), 1, null, conversationId, messageId, 3L,
                subject.userId(), "messaging.message.created", Map.of(), OffsetDateTime.now());
        when(repository.append(
                subject.tenantId(), null, conversationId, messageId, subject.userId(),
                "messaging.message.created", Map.of())).thenReturn(event);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        recorder.conversationEvent(
                subject, "messaging.message.created", conversationId, messageId, Map.of());

        verify(repository).append(
                subject.tenantId(), null, conversationId, messageId, subject.userId(),
                "messaging.message.created", Map.of());
        verify(publisher).publishAfterCommit(event);
    }

    private MessagingRequestContext.Subject subject() {
        return new MessagingRequestContext.Subject(
                100, 1, UUID.randomUUID(), "Test User",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MESSAGING:CREATE"), Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"messaging.read-cursor.updated", "messaging.privacy-preferences.updated"})
    void selfEventsCannotAccidentallyBeRecordedAsConversationOrTenantEvents(String eventType) {
        MessagingRealtimeRepository repository = mock(MessagingRealtimeRepository.class);
        MessagingEventRecorder recorder = new MessagingEventRecorder(repository, mock(MessagingRealtimePublisher.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        UUID conversation = UUID.randomUUID();
        recorder.conversationEvent(subject(), eventType, conversation, null, Map.of());
        recorder.tenantEvent(subject(), eventType, Map.of());
        verify(repository).append(1, 100L, conversation, null, 100, eventType, Map.of());
        verify(repository).append(1, 100L, null, null, 100, eventType, Map.of());
    }
}
