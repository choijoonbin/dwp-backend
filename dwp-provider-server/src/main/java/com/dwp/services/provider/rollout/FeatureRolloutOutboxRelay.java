package com.dwp.services.provider.rollout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class FeatureRolloutOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(FeatureRolloutOutboxRelay.class);

    private final FeatureRolloutDecisionOutboxRepository repository;
    private final FeatureRolloutDecisionEventPublisher publisher;
    private final Clock clock;
    private final boolean enabled;
    private final String workerId;
    private final int batchSize;
    private final int maximumAttempts;
    private final Duration lease;

    @Autowired
    public FeatureRolloutOutboxRelay(
            FeatureRolloutDecisionOutboxRepository repository,
            FeatureRolloutDecisionEventPublisher publisher,
            @Value("${dwp.provider.product-surface-rollout.relay-enabled:false}") boolean enabled,
            @Value("${dwp.provider.product-surface-rollout.relay-worker-id:provider-rollout}")
                    String workerId,
            @Value("${dwp.provider.product-surface-rollout.relay-batch-size:100}") int batchSize,
            @Value("${dwp.provider.product-surface-rollout.relay-maximum-attempts:10}")
                    int maximumAttempts,
            @Value("${dwp.provider.product-surface-rollout.relay-lease:30s}") Duration lease) {
        this(repository, publisher, Clock.systemUTC(), enabled, workerId,
                batchSize, maximumAttempts, lease);
    }

    FeatureRolloutOutboxRelay(
            FeatureRolloutDecisionOutboxRepository repository,
            FeatureRolloutDecisionEventPublisher publisher,
            Clock clock,
            boolean enabled,
            String workerId,
            int batchSize,
            int maximumAttempts,
            Duration lease) {
        if (enabled && publisher == FeatureRolloutDecisionEventPublisher.NOOP) {
            throw new IllegalStateException(
                    "Rollout invalidation relay requires an approved publisher adapter");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 200) {
            throw new IllegalArgumentException("A bounded relay worker id is required");
        }
        if (batchSize < 1 || batchSize > 500 || maximumAttempts < 1
                || lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid rollout relay limits");
        }
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.enabled = enabled;
        this.workerId = workerId;
        this.batchSize = batchSize;
        this.maximumAttempts = maximumAttempts;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString =
            "${dwp.provider.product-surface-rollout.relay-poll-delay-ms:2000}")
    public void pollSafely() {
        if (!enabled) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException exception) {
            log.error("Product surface rollout invalidation relay failed", exception);
        }
    }

    void pollOnce() {
        repository.releaseExpired(Instant.now(clock));
        List<FeatureRolloutDecisionOutboxRepository.DecisionEvent> events =
                repository.claim(workerId, batchSize, lease);
        if (events.isEmpty()) {
            return;
        }
        try {
            publisher.publish(events);
            repository.markPublished(events.stream()
                    .map(FeatureRolloutDecisionOutboxRepository.DecisionEvent::eventId)
                    .toList());
        } catch (RuntimeException batchFailure) {
            publishIndividually(events);
            log.warn("Rollout invalidation batch failed; isolated {} events",
                    events.size(), batchFailure);
        }
    }

    private void publishIndividually(
            List<FeatureRolloutDecisionOutboxRepository.DecisionEvent> events) {
        List<UUID> published = new ArrayList<>();
        for (FeatureRolloutDecisionOutboxRepository.DecisionEvent event : events) {
            try {
                publisher.publish(List.of(event));
                published.add(event.eventId());
            } catch (RuntimeException failure) {
                repository.markFailed(
                        event.eventId(), event.attempt(), maximumAttempts, failure.getMessage());
            }
        }
        repository.markPublished(published);
    }
}
