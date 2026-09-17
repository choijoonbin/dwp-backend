package com.dwp.services.platform.dwaion;

import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository.Delivery;
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
        name = "dwp.platform.dwaion-handoff.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PlatformDwaionHandoffRelay {
    private static final Logger log = LoggerFactory.getLogger(PlatformDwaionHandoffRelay.class);
    private final PlatformDwaionHandoffOutboxRepository outbox;
    private final PlatformDwaionHandoffObserverClient observer;
    private final int batchSize;
    private final int maximumAttempts;
    private final String workerId = "platform-dwaion-" + UUID.randomUUID();

    public PlatformDwaionHandoffRelay(
            PlatformDwaionHandoffOutboxRepository outbox,
            PlatformDwaionHandoffObserverClient observer,
            @Value("${dwp.platform.dwaion-handoff.batch-size:25}") int batchSize,
            @Value("${dwp.platform.dwaion-handoff.maximum-attempts:10}") int maximumAttempts) {
        this.outbox = outbox;
        this.observer = observer;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.maximumAttempts = Math.max(1, Math.min(maximumAttempts, 100));
    }

    @Scheduled(
            fixedDelayString = "${dwp.platform.dwaion-handoff.poll-delay-ms:2000}",
            initialDelayString = "${dwp.platform.dwaion-handoff.initial-delay-ms:5000}")
    public void publishPending() {
        for (Delivery claimed : outbox.claim(batchSize, workerId)) deliver(claimed);
    }

    void deliver(Delivery claimed) {
        Delivery current = claimed;
        try {
            for (int stage = 0; stage < 3; stage++) {
                Optional<Delivery> next = outbox.advance(
                        current, workerId, observer.observe(current));
                if (next.isEmpty()) return;
                current = next.get();
            }
            throw new IllegalStateException(
                    "DWAI-ON handoff exceeded its bounded observation sequence");
        } catch (RuntimeException exception) {
            final boolean deadLettered;
            try {
                deadLettered = outbox.retry(
                        current, workerId, maximumAttempts, exception.getMessage());
            } catch (RuntimeException leaseLost) {
                log.warn("DWAI-ON platform handoff lease changed for {}", current.handoffId());
                return;
            }
            if (deadLettered) {
                log.warn("DWAI-ON platform handoff delivery dead-lettered for {} on attempt {}",
                        current.handoffId(), current.attemptCount());
            } else {
                log.warn("DWAI-ON platform handoff delivery will retry for {} on attempt {}",
                        current.handoffId(), current.attemptCount());
            }
        }
    }
}
