package com.dwp.core.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.AuditEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditOutboxRelayTest {

    @Test
    void isolatesPermanentlyRejectedEventWithoutBlockingValidEvents() {
        AuditOutboxRepository repository = mock(AuditOutboxRepository.class);
        UUID firstId = UUID.randomUUID();
        UUID rejectedId = UUID.randomUUID();
        UUID lastId = UUID.randomUUID();
        List<AuditOutboxRepository.ClaimedEvent> claimed = List.of(
                new AuditOutboxRepository.ClaimedEvent(firstId, event("valid.first"), 1),
                new AuditOutboxRepository.ClaimedEvent(rejectedId, event("invalid.event"), 1),
                new AuditOutboxRepository.ClaimedEvent(lastId, event("valid.last"), 1));
        when(repository.claim(anyString(), eq(3), anyInt())).thenReturn(claimed);

        AuditEventPublisher publisher = events -> events.stream()
                .anyMatch(event -> event.action().startsWith("invalid"))
                ? AuditEventPublisher.DeliveryResult.REJECTED
                : AuditEventPublisher.DeliveryResult.ACCEPTED;
        AuditOutboxRelay relay = new AuditOutboxRelay(
                repository, publisher, true, "test-worker", 3, 30, 20,
                Duration.ofSeconds(2), 7);

        relay.relayOnce();

        verify(repository).markPublished(List.of(firstId));
        verify(repository).markPublished(List.of(lastId));
        verify(repository).markFailed(eq(rejectedId), eq(20), eq(20), contains("rejected"));
    }

    private static AuditEvent event(String action) {
        return AuditEvent.builder()
                .tenantId(1L)
                .category("ADMIN_CHANGE")
                .action(action)
                .sourceService("test-service")
                .targetType("TEST")
                .targetId(action)
                .build();
    }
}
