package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainEventOutboxRelayTest {

    @Test
    void batchFailureIsolatesPoisonEventAndPublishesHealthyEvents() {
        DomainEventOutboxRepository repository = mock(DomainEventOutboxRepository.class);
        UUID firstId = UUID.randomUUID();
        UUID poisonId = UUID.randomUUID();
        UUID lastId = UUID.randomUUID();
        DomainEventEnvelope first = event("dwp.people.first", 1);
        DomainEventEnvelope poison = event("dwp.people.poison", 1);
        DomainEventEnvelope last = event("dwp.people.last", 1);
        when(repository.claim("worker", 10, 30)).thenReturn(List.of(
                new DomainEventOutboxRepository.ClaimedEvent(firstId, first, 1),
                new DomainEventOutboxRepository.ClaimedEvent(poisonId, poison, 2),
                new DomainEventOutboxRepository.ClaimedEvent(lastId, last, 1)));
        AtomicInteger calls = new AtomicInteger();
        DomainEventPublisher publisher = events -> {
            calls.incrementAndGet();
            if (events.size() > 1 || events.get(0).id().equals(poison.id())) {
                throw new IllegalStateException("broker rejected poison event");
            }
        };
        DomainEventOutboxRelay relay = new DomainEventOutboxRelay(
                repository, publisher, true, "worker", 10, 30, 5, Duration.ofSeconds(2));

        relay.pollOnce();

        verify(repository).releaseExpiredLeases(org.mockito.ArgumentMatchers.any(Instant.class));
        verify(repository).markFailed(poisonId, 2, 5, "broker rejected poison event");
        verify(repository).markPublished(List.of(firstId, lastId));
        org.assertj.core.api.Assertions.assertThat(calls).hasValue(4);
    }

    private DomainEventEnvelope event(String type, long sequence) {
        return new DomainEventEnvelope(
                "1.0", UUID.randomUUID(), "dwp-people-server", type,
                1, Instant.now(), "worker/7", 1L, "WORKER", type,
                sequence, "corr-7", null, null,
                new ObjectMapper().createObjectNode(), Map.of());
    }
}
