package com.dwp.services.people.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dwp.identity-sync.enabled", havingValue = "true")
public class IdentitySyncOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(IdentitySyncOutboxPublisher.class);

    private final IdentitySyncOutboxRepository repository;
    private final IdentitySyncClient client;
    private final int batchSize;
    private final int maximumAttempts;

    public IdentitySyncOutboxPublisher(
            IdentitySyncOutboxRepository repository,
            IdentitySyncClient client,
            @Value("${dwp.identity-sync.batch-size:50}") int batchSize,
            @Value("${dwp.identity-sync.maximum-attempts:10}") int maximumAttempts) {
        this.repository = repository;
        this.client = client;
        this.batchSize = Math.min(200, Math.max(1, batchSize));
        this.maximumAttempts = Math.max(1, maximumAttempts);
    }

    @Scheduled(fixedDelayString = "${dwp.identity-sync.publish-interval-ms:2000}")
    public void publishPending() {
        for (IdentitySyncOutboxRepository.PendingEvent event : repository.claim(batchSize)) {
            try {
                client.publish(event);
                repository.markPublished(event.eventId());
            } catch (RuntimeException exception) {
                repository.markFailed(
                        event.eventId(), event.attemptCount(), maximumAttempts,
                        exception.getMessage());
                log.warn(
                        "Workforce identity sync failed for event {} on attempt {}",
                        event.eventId(), event.attemptCount());
            }
        }
    }
}
