package com.dwp.services.people.identity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentitySyncOutboxPublisherTest {

    private final IdentitySyncOutboxRepository repository = mock(IdentitySyncOutboxRepository.class);
    private final IdentitySyncClient client = mock(IdentitySyncClient.class);
    private final IdentitySyncOutboxPublisher publisher =
            new IdentitySyncOutboxPublisher(repository, client, 50, 10);

    @Test
    void marksSuccessfullyDeliveredEventAsPublished() {
        IdentitySyncOutboxRepository.PendingEvent event = event(1);
        when(repository.claim(50)).thenReturn(List.of(event));

        publisher.publishPending();

        verify(client).publish(event);
        verify(repository).markPublished(event.eventId());
    }

    @Test
    void recordsFailureForRetry() {
        IdentitySyncOutboxRepository.PendingEvent event = event(3);
        when(repository.claim(50)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("unavailable")).when(client).publish(event);

        publisher.publishPending();

        verify(repository).markFailed(event.eventId(), 3, 10, "unavailable");
    }

    private IdentitySyncOutboxRepository.PendingEvent event(int attempt) {
        return new IdentitySyncOutboxRepository.PendingEvent(
                UUID.randomUUID(), UUID.randomUUID(), "{}", "correlation", attempt);
    }
}
