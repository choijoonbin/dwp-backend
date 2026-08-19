package com.dwp.services.approval.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "dwp.approval.integration-relay.enabled",
        havingValue = "true")
public class ApprovalIntegrationRelay {

    private static final Logger log = LoggerFactory.getLogger(ApprovalIntegrationRelay.class);

    private final ApprovalIntegrationOutboxRepository repository;
    private final ApprovalIntegrationPublisher publisher;
    private final int batchSize;
    private final int maximumAttempts;
    private final String workerId = "approval-relay-" + UUID.randomUUID();

    public ApprovalIntegrationRelay(
            ApprovalIntegrationOutboxRepository repository,
            ApprovalIntegrationPublisher publisher,
            @Value("${dwp.approval.integration-relay.batch-size:50}") int batchSize,
            @Value("${dwp.approval.integration-relay.maximum-attempts:10}") int maximumAttempts) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.maximumAttempts = Math.max(1, Math.min(maximumAttempts, 100));
    }

    @Scheduled(fixedDelayString = "${dwp.approval.integration-relay.poll-delay-ms:2000}")
    public void publishPending() {
        for (ApprovalIntegrationOutboxRepository.PendingEvent event
                : repository.claim(batchSize, workerId)) {
            try {
                publisher.publish(event);
                repository.markPublished(event.outboxId(), workerId);
            } catch (RuntimeException exception) {
                repository.markFailed(
                        event.outboxId(), workerId, event.attemptCount(),
                        maximumAttempts, exception.getMessage());
                log.warn(
                        "Approval event delivery failed for {} on attempt {}",
                        event.eventId(), event.attemptCount());
            }
        }
    }
}
