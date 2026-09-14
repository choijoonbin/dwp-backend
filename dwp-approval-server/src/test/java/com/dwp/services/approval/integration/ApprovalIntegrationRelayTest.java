package com.dwp.services.approval.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

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
        dispatchCurrent();

        relay.publishPending();

        verify(publisher).publish(event);
        verify(repository).publishCurrent(
                org.mockito.ArgumentMatchers.eq(event),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void movesBrokerFailuresThroughTheGovernedRetryPath() {
        ApprovalIntegrationOutboxRepository.PendingEvent event = event(2);
        when(repository.claim(
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(event));
        dispatchCurrent();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(event);

        relay.publishPending();

        verify(repository).markFailed(
                org.mockito.ArgumentMatchers.eq(event),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq("broker unavailable"));
    }

    private void dispatchCurrent() {
        doAnswer(invocation -> {
            java.util.function.Consumer<ApprovalIntegrationOutboxRepository.PendingEvent> network=invocation.getArgument(2);
            network.accept(invocation.getArgument(0));return true;
        }).when(repository).publishCurrent(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }

    private ApprovalIntegrationOutboxRepository.PendingEvent event(int attemptCount) {
        return new ApprovalIntegrationOutboxRepository.PendingEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1L, UUID.randomUUID(),
                "approval.request.submitted", "{}", attemptCount,"a".repeat(64),java.time.Instant.now().plusSeconds(30));
    }
}
