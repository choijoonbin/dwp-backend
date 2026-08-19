package com.dwp.services.approval.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalIntegrationRelayTest {

    private final ApprovalIntegrationOutboxRepository repository =
            mock(ApprovalIntegrationOutboxRepository.class);
    private final ApprovalIntegrationPublisher publisher =
            mock(ApprovalIntegrationPublisher.class);
    private final ApprovalIntegrationRelay relay =
            new ApprovalIntegrationRelay(repository, publisher, 50, 3);

    @Test
    void marksAnEventPublishedOnlyAfterTheBrokerAcknowledgesIt() {
        ApprovalIntegrationOutboxRepository.PendingEvent event = event(1);
        when(repository.claim(
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(event));

        relay.publishPending();

        verify(publisher).publish(event);
        verify(repository).markPublished(
                org.mockito.ArgumentMatchers.eq(event.outboxId()),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void movesBrokerFailuresThroughTheGovernedRetryPath() {
        ApprovalIntegrationOutboxRepository.PendingEvent event = event(2);
        when(repository.claim(
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(event);

        relay.publishPending();

        verify(repository).markFailed(
                org.mockito.ArgumentMatchers.eq(event.outboxId()),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq("broker unavailable"));
    }

    private ApprovalIntegrationOutboxRepository.PendingEvent event(int attemptCount) {
        return new ApprovalIntegrationOutboxRepository.PendingEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1L, UUID.randomUUID(),
                "approval.request.submitted", "{}", attemptCount);
    }
}
