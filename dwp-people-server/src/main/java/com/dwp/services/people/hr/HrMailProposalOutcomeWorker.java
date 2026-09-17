package com.dwp.services.people.hr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HrMailProposalOutcomeWorker {

    private static final Logger log = LoggerFactory.getLogger(HrMailProposalOutcomeWorker.class);

    private final HrMailProposalOutcomeOutboxRepository repository;
    private final HrMailProposalOutcomeClient client;
    private final int batchSize;
    private final int maximumAttempts;

    @Autowired
    public HrMailProposalOutcomeWorker(
            HrMailProposalOutcomeOutboxRepository repository,
            HrMailProposalOutcomeClient client,
            @Value("${dwp.mail-proposal-outcomes.batch-size:50}") int batchSize,
            @Value("${dwp.mail-proposal-outcomes.maximum-attempts:10}")
            int maximumAttempts) {
        this.repository = repository;
        this.client = client;
        this.batchSize = Math.min(200, Math.max(1, batchSize));
        this.maximumAttempts = Math.max(1, maximumAttempts);
    }

    HrMailProposalOutcomeWorker(
            HrMailProposalOutcomeOutboxRepository repository,
            HrMailProposalOutcomeClient client,
            int batchSize) {
        this(repository, client, batchSize, 10);
    }

    @Scheduled(fixedDelayString = "${dwp.mail-proposal-outcomes.publish-interval-ms:2000}")
    public void publishPending() {
        for (HrMailProposalOutcomeOutboxRepository.PendingOutcome outcome
                : repository.claim(batchSize)) {
            try {
                client.record(outcome);
                repository.markPublished(outcome.outcomeId());
            } catch (RuntimeException exception) {
                recordFailure(outcome, exception);
            }
        }
    }

    private void recordFailure(
            HrMailProposalOutcomeOutboxRepository.PendingOutcome outcome,
            RuntimeException exception) {
        try {
            boolean retryable = !(exception instanceof HrMailProposalOutcomeClient.DeliveryException)
                    || ((HrMailProposalOutcomeClient.DeliveryException) exception).retryable();
            repository.markFailed(
                    outcome.outcomeId(), outcome.attemptCount(), maximumAttempts,
                    retryable, exception.getMessage());
        } catch (RuntimeException persistenceFailure) {
            log.error(
                    "Unable to persist retry state for HR Mail proposal outcome {}",
                    outcome.outcomeId(), persistenceFailure);
        }
        log.warn(
                "HR Mail proposal outcome {} delivery failed on attempt {}",
                outcome.outcomeId(), outcome.attemptCount());
    }
}
