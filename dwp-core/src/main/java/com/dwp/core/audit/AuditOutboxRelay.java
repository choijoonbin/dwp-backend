package com.dwp.core.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.AuditEventPublisher;
import com.dwp.audit.AuditEventPublisher.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-worker relay. PostgreSQL leases make it safe when multiple service instances run. */
public class AuditOutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);

    private final AuditOutboxRepository repository;
    private final AuditEventPublisher publisher;
    private final boolean enabled;
    private final String workerId;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final Duration pollInterval;
    private final int publishedRetentionDays;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private long cycles;

    public AuditOutboxRelay(
            AuditOutboxRepository repository,
            AuditEventPublisher publisher,
            boolean enabled,
            String workerId,
            int batchSize,
            int leaseSeconds,
            int maximumAttempts,
            Duration pollInterval,
            int publishedRetentionDays) {
        this.repository = repository;
        this.publisher = publisher;
        this.enabled = enabled;
        this.workerId = workerId;
        this.batchSize = Math.max(1, Math.min(200, batchSize));
        this.leaseSeconds = Math.max(10, leaseSeconds);
        this.maximumAttempts = Math.max(3, maximumAttempts);
        this.pollInterval = pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofSeconds(2)
                : pollInterval;
        this.publishedRetentionDays = Math.max(1, publishedRetentionDays);
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dwp-audit-outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runSafely,
                500,
                Math.max(250, pollInterval.toMillis()),
                TimeUnit.MILLISECONDS);
        log.info("DWP durable audit relay started; worker={}", workerId);
    }

    private void runSafely() {
        if (!running.get()) return;
        try {
            relayOnce();
            cycles++;
            if (cycles % 1_000 == 0) {
                repository.deletePublishedBefore(
                        Instant.now().minus(Duration.ofDays(publishedRetentionDays)));
            }
        } catch (RuntimeException exception) {
            log.warn("Audit outbox relay cycle failed; worker={} error={}",
                    workerId, exception.getClass().getSimpleName());
        }
    }

    public void relayOnce() {
        List<AuditOutboxRepository.ClaimedEvent> claimed = repository.claim(
                workerId, batchSize, leaseSeconds);
        if (claimed.isEmpty()) return;
        publishOrIsolate(claimed);
    }

    private void publishOrIsolate(List<AuditOutboxRepository.ClaimedEvent> claimed) {
        List<AuditEvent> events = claimed.stream()
                .map(AuditOutboxRepository.ClaimedEvent::event)
                .toList();
        DeliveryResult result = publisher.publish(events);
        if (result == DeliveryResult.ACCEPTED) {
            int published = repository.markPublished(claimed);
            if (published != claimed.size()) {
                log.warn("Audit outbox publish completion lost lease ownership; worker={} expected={} updated={}",
                        workerId, claimed.size(), published);
            }
            return;
        }
        if (result == DeliveryResult.REJECTED && claimed.size() > 1) {
            int midpoint = claimed.size() / 2;
            publishOrIsolate(claimed.subList(0, midpoint));
            publishOrIsolate(claimed.subList(midpoint, claimed.size()));
            return;
        }
        String error = result == DeliveryResult.REJECTED
                ? "Audit event rejected by collector after batch isolation"
                : "Audit collector delivery is temporarily unavailable";
        for (AuditOutboxRepository.ClaimedEvent item : claimed) {
            boolean marked = repository.markFailed(
                    item,
                    result == DeliveryResult.REJECTED ? maximumAttempts : item.attempts(),
                    maximumAttempts,
                    error);
            if (!marked) {
                log.warn("Audit outbox failure completion lost lease ownership; worker={} outbox={}",
                        workerId, item.outboxId());
            }
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    public static String workerId(String serviceName, String instance) {
        return serviceName + ":" + instance + ":" + UUID.randomUUID();
    }
}
