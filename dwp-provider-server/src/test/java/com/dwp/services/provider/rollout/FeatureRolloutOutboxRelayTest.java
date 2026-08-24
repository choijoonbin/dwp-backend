package com.dwp.services.provider.rollout;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRolloutOutboxRelayTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void publishesAndAcknowledgesAnAtLeastOnceInvalidation() {
        FeatureRolloutDecisionOutboxRepository repository =
                mock(FeatureRolloutDecisionOutboxRepository.class);
        FeatureRolloutDecisionEventPublisher publisher =
                mock(FeatureRolloutDecisionEventPublisher.class);
        var event = event(1);
        when(repository.claim("relay-1", 100, Duration.ofSeconds(30)))
                .thenReturn(List.of(event));
        FeatureRolloutOutboxRelay relay = new FeatureRolloutOutboxRelay(
                repository, publisher, CLOCK, true, "relay-1", 100, 10,
                Duration.ofSeconds(30));

        relay.pollOnce();

        verify(repository).releaseExpired(CLOCK.instant());
        verify(publisher).publish(List.of(event));
        verify(repository).markPublished(List.of(event.eventId()));
    }

    @Test
    void isolatesPublicationFailureAndSchedulesRetry() {
        FeatureRolloutDecisionOutboxRepository repository =
                mock(FeatureRolloutDecisionOutboxRepository.class);
        FeatureRolloutDecisionEventPublisher publisher =
                mock(FeatureRolloutDecisionEventPublisher.class);
        var event = event(2);
        when(repository.claim("relay-1", 100, Duration.ofSeconds(30)))
                .thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(org.mockito.ArgumentMatchers.anyList());
        FeatureRolloutOutboxRelay relay = new FeatureRolloutOutboxRelay(
                repository, publisher, CLOCK, true, "relay-1", 100, 10,
                Duration.ofSeconds(30));

        relay.pollOnce();

        verify(repository).markFailed(event.eventId(), 2, 10, "broker unavailable");
    }

    private static FeatureRolloutDecisionOutboxRepository.DecisionEvent event(int attempt) {
        return new FeatureRolloutDecisionOutboxRepository.DecisionEvent(
                UUID.randomUUID(), null, "ALL",
                "ux.product-surfaces.communications.v1", 7L, "ENABLED",
                attempt, CLOCK.instant());
    }
}
