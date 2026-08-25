package com.dwp.core.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Lease-based relay that isolates failures per event and never starts without an approved adapter. */
public class DomainEventOutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DomainEventOutboxRelay.class);

    private final DomainEventOutboxRepository repository;
    private final DomainEventPublisher publisher;
    private final boolean enabled;
    private final String workerId;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final Duration pollInterval;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public DomainEventOutboxRelay(
            DomainEventOutboxRepository repository,
            DomainEventPublisher publisher,
            boolean enabled,
            String workerId,
            int batchSize,
            int leaseSeconds,
            int maximumAttempts,
            Duration pollInterval) {
        this.repository = repository;
        this.publisher = publisher;
        this.enabled = enabled;
        this.workerId = workerId;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
        this.pollInterval = pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofSeconds(2)
                : pollInterval;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        if (!enabled) {
            log.info("DWP domain-event transport is disabled until this service completes producer onboarding");
            return;
        }
        if (publisher == DomainEventPublisher.NOOP) {
            throw new IllegalStateException(
                    "Domain-event transport was enabled without a publisher adapter.");
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dwp-domain-event-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::pollSafely,
                0,
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    void pollOnce() {
        repository.releaseExpiredLeases(Instant.now());
        List<DomainEventOutboxRepository.ClaimedEvent> claimed =
                repository.claim(workerId, batchSize, leaseSeconds);
        if (claimed.isEmpty()) return;

        try {
            publisher.publish(claimed.stream()
                    .map(DomainEventOutboxRepository.ClaimedEvent::event)
                    .toList());
            markPublished(claimed);
        } catch (RuntimeException batchFailure) {
            publishIndividually(claimed, batchFailure);
        }
    }

    private void publishIndividually(
            List<DomainEventOutboxRepository.ClaimedEvent> claimed,
            RuntimeException batchFailure) {
        List<DomainEventOutboxRepository.ClaimedEvent> published = new ArrayList<>();
        for (DomainEventOutboxRepository.ClaimedEvent item : claimed) {
            try {
                publisher.publish(List.of(item.event()));
                published.add(item);
            } catch (RuntimeException eventFailure) {
                boolean marked = repository.markFailed(
                        item.workerId(), item.leaseToken(), item.outboxId(),
                        item.attempts(), maximumAttempts, eventFailure.getMessage());
                if (!marked) {
                    log.warn(
                            "Ignored stale domain-event failure outcome for outbox {}",
                            item.outboxId());
                }
            }
        }
        markPublished(published);
        log.warn(
                "Domain-event batch publish failed; isolated {} event outcomes",
                claimed.size(), batchFailure);
    }

    private void markPublished(List<DomainEventOutboxRepository.ClaimedEvent> published) {
        Map<LeaseOwner, List<UUID>> byLease = new LinkedHashMap<>();
        for (DomainEventOutboxRepository.ClaimedEvent item : published) {
            byLease.computeIfAbsent(
                            new LeaseOwner(item.workerId(), item.leaseToken()),
                            ignored -> new ArrayList<>())
                    .add(item.outboxId());
        }
        byLease.forEach((lease, ids) -> {
            int changed = repository.markPublished(
                    lease.workerId(), lease.leaseToken(), ids);
            if (changed != ids.size()) {
                log.warn(
                        "Ignored {} stale domain-event publish outcomes after lease loss",
                        ids.size() - changed);
            }
        });
    }

    private void pollSafely() {
        if (!running) return;
        try {
            pollOnce();
        } catch (RuntimeException exception) {
            log.error("Domain-event outbox polling failed", exception);
        }
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive.");
        return value;
    }

    public static String workerId(String serviceName, String serviceInstance) {
        return serviceName + ':' + serviceInstance + ':' + UUID.randomUUID();
    }

    private record LeaseOwner(String workerId, String leaseToken) {
    }
}
