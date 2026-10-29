package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationOutboxRelayService.RelayResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public final class NotificationOutboxOperationalMonitor implements HealthIndicator {

    private final boolean enabled;
    private final Duration startupGrace;
    private final Duration staleAfter;
    private final Duration oldestAgeThreshold;
    private final long backlogThreshold;
    private final Instant startedAt = Instant.now();
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong leaseLostLastCycle = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadCounter;
    private final Counter leaseLostCounter;
    private final Counter cycleFailureCounter;

    public NotificationOutboxOperationalMonitor(
            MeterRegistry registry,
            @Value("${dwp.notification.outbox.enabled:true}") boolean enabled,
            @Value("${dwp.notification.outbox.health.startup-grace:PT30S}")
            Duration startupGrace,
            @Value("${dwp.notification.outbox.health.stale-after:PT2M}")
            Duration staleAfter,
            @Value("${dwp.notification.outbox.health.oldest-age-threshold:PT15M}")
            Duration oldestAgeThreshold,
            @Value("${dwp.notification.outbox.health.backlog-threshold:10000}")
            long backlogThreshold) {
        if (startupGrace.isNegative()
                || staleAfter.isZero() || staleAfter.isNegative()
                || oldestAgeThreshold.isZero() || oldestAgeThreshold.isNegative()
                || backlogThreshold < 1) {
            throw new IllegalArgumentException("Notification outbox health thresholds are invalid.");
        }
        this.enabled = enabled;
        this.startupGrace = startupGrace;
        this.staleAfter = staleAfter;
        this.oldestAgeThreshold = oldestAgeThreshold;
        this.backlogThreshold = backlogThreshold;
        Gauge.builder("dwp.notification.outbox.backlog", backlog, AtomicLong::get)
                .description("Unpublished live notification outbox events")
                .register(registry);
        Gauge.builder("dwp.notification.outbox.retries", retries, AtomicLong::get)
                .description("Live notification outbox events already attempted")
                .register(registry);
        Gauge.builder("dwp.notification.outbox.dead", dead, AtomicLong::get)
                .description("Dead notification outbox events")
                .register(registry);
        Gauge.builder(
                        "dwp.notification.outbox.oldest.age.seconds",
                        oldestAgeSeconds,
                        AtomicLong::get)
                .description("Age of the oldest live notification outbox event")
                .register(registry);
        Gauge.builder(
                        "dwp.notification.outbox.scheduler.last.success.epoch.seconds",
                        lastSuccessEpochSeconds,
                        AtomicLong::get)
                .description("Epoch second of the last successful complete relay cycle")
                .register(registry);
        publishedCounter = counter(registry, "dwp.notification.outbox.published.total");
        failedCounter = counter(registry, "dwp.notification.outbox.failed.total");
        deadCounter = counter(registry, "dwp.notification.outbox.dead.total");
        leaseLostCounter = counter(
                registry, "dwp.notification.outbox.lease.lost.total");
        cycleFailureCounter = counter(
                registry, "dwp.notification.outbox.scheduler.failures.total");
    }

    void recordRelay(RelayResult result) {
        if (result.published() > 0) publishedCounter.increment(result.published());
        if (result.failed() > 0) failedCounter.increment(result.failed());
        if (result.dead() > 0) deadCounter.increment(result.dead());
        if (result.leaseLost() > 0) leaseLostCounter.increment(result.leaseLost());
    }

    void recordCycle(OutboxSnapshot snapshot, boolean successful, Instant now, String failure) {
        backlog.set(snapshot.backlog());
        retries.set(snapshot.retries());
        dead.set(snapshot.dead());
        leaseLostLastCycle.set(snapshot.leaseLost());
        oldestAgeSeconds.set(snapshot.oldestCreatedAt() == null
                ? 0
                : Math.max(0, Duration.between(snapshot.oldestCreatedAt(), now).toSeconds()));
        if (successful) {
            lastSuccessEpochSeconds.set(now.getEpochSecond());
            lastFailure.set(null);
        } else {
            cycleFailureCounter.increment();
            lastFailure.set(failure == null ? "RELAY_CYCLE_FAILED" : failure);
        }
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.unknown().withDetail("state", "DISABLED").build();
        }
        Instant now = Instant.now();
        long lastSuccess = lastSuccessEpochSeconds.get();
        boolean startup = lastSuccess == 0
                && Duration.between(startedAt, now).compareTo(startupGrace) <= 0;
        boolean stale = lastSuccess == 0
                ? !startup
                : Duration.between(Instant.ofEpochSecond(lastSuccess), now)
                        .compareTo(staleAfter) > 0;
        boolean blocked = stale
                || dead.get() > 0
                || leaseLostLastCycle.get() > 0
                || backlog.get() > backlogThreshold
                || oldestAgeSeconds.get() > oldestAgeThreshold.toSeconds();
        Health.Builder health = startup
                ? Health.unknown()
                : blocked ? Health.down() : Health.up();
        health.withDetail("state", startup ? "STARTING" : blocked ? "BLOCKED" : "LIVE")
                .withDetail("backlog", backlog.get())
                .withDetail("retries", retries.get())
                .withDetail("dead", dead.get())
                .withDetail("leaseLostLastCycle", leaseLostLastCycle.get())
                .withDetail("oldestAgeSeconds", oldestAgeSeconds.get())
                .withDetail("schedulerStale", stale);
        if (lastSuccess > 0) {
            health.withDetail("lastSuccessfulCycle", Instant.ofEpochSecond(lastSuccess));
        }
        if (lastFailure.get() != null) {
            health.withDetail("lastFailure", lastFailure.get());
        }
        return health.build();
    }

    private Counter counter(MeterRegistry registry, String name) {
        return Counter.builder(name).register(registry);
    }

    record OutboxSnapshot(
            long backlog,
            long retries,
            long dead,
            long leaseLost,
            Instant oldestCreatedAt) {
    }
}
