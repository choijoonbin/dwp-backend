package com.dwp.services.approval.dwaion;

import com.dwp.services.approval.dwaion.DwaionProposalHandoffOutboxRepository.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "dwp.approval.dwaion-handoff.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DwaionProposalHandoffRelay {
    private static final Logger log = LoggerFactory.getLogger(DwaionProposalHandoffRelay.class);
    private final DwaionProposalHandoffOutboxRepository outbox;
    private final DwaionProposalHandoffObserverClient observer;
    private final int batchSize;
    private final String workerId = "approval-dwaion-" + UUID.randomUUID();

    public DwaionProposalHandoffRelay(
            DwaionProposalHandoffOutboxRepository outbox,
            DwaionProposalHandoffObserverClient observer,
            @Value("${dwp.approval.dwaion-handoff.batch-size:25}") int batchSize) {
        this.outbox = outbox;
        this.observer = observer;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            fixedDelayString = "${dwp.approval.dwaion-handoff.poll-delay-ms:2000}",
            initialDelayString = "${dwp.approval.dwaion-handoff.initial-delay-ms:5000}")
    public void publishPending() {
        for (Delivery claimed : outbox.claim(batchSize, workerId)) deliver(claimed);
    }

    void deliver(Delivery claimed) {
        Delivery current = claimed;
        try {
            for (int stage = 0; stage < 3; stage++) {
                Optional<Delivery> next = outbox.advance(current, workerId, observer.observe(current));
                if (next.isEmpty()) return;
                current = next.get();
            }
            throw new IllegalStateException("DWAI-ON handoff exceeded its bounded observation sequence");
        } catch (RuntimeException exception) {
            try {
                outbox.retry(current, workerId, exception.getMessage());
            } catch (RuntimeException leaseLost) {
                log.warn("DWAI-ON Approval handoff lease changed for {}", current.handoffId());
                return;
            }
            log.warn("DWAI-ON Approval handoff delivery will retry for {} on attempt {}",
                    current.handoffId(), current.attemptCount());
        }
    }
}
