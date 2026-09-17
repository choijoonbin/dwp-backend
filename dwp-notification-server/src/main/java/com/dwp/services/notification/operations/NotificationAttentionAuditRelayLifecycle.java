package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationAttentionAuditRelayService.RelayResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NotificationAttentionAuditRelayLifecycle
        implements SmartLifecycle, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
            NotificationAttentionAuditRelayLifecycle.class);
    private static final int TENANT_PAGE_SIZE = 500;

    private final NotificationMaintenanceTenantRepository tenants;
    private final NotificationAttentionAuditRelayService relay;
    private final boolean enabled;
    private final boolean transportConfigured;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<String> lastFailure = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
    private final AtomicLong deadEvents = new AtomicLong();
    private ScheduledExecutorService executor;

    public NotificationAttentionAuditRelayLifecycle(
            NotificationMaintenanceTenantRepository tenants,
            NotificationAttentionAuditRelayService relay,
            @Value("${dwp.notification.attention-audit.enabled:true}") boolean enabled,
            @Value("${dwp.audit.collector-url:}") String collectorUrl,
            @Value("${dwp.audit.ingest-token:}") String ingestToken,
            @Value("${dwp.notification.attention-audit.poll-interval:PT2S}")
            Duration pollInterval) {
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Attention audit poll interval must be positive.");
        }
        this.tenants = tenants;
        this.relay = relay;
        this.enabled = enabled;
        this.transportConfigured = collectorUrl != null && !collectorUrl.isBlank()
                && ingestToken != null && !ingestToken.isBlank();
        this.pollInterval = pollInterval;
    }

    @Override
    public void start() {
        if (!enabled || !transportConfigured || !running.compareAndSet(false, true)) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dwp-attention-audit-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runSafely,
                500,
                Math.max(250, pollInterval.toMillis()),
                TimeUnit.MILLISECONDS);
        log.info("Notification attention audit relay started");
    }

    void runOnce(Instant now) {
        long cursor = Long.MIN_VALUE;
        boolean failed = false;
        long observedDeadBacklog = 0L;
        while (true) {
            List<Long> page = tenants.activeTenantIdsAfter(cursor, TENANT_PAGE_SIZE);
            for (Long tenantId : page) {
                try {
                    RelayResult result = relay.relayTenant(tenantId, now);
                    if (result.dead() > 0) {
                        log.error(
                                "Attention audit relay retained poison evidence; tenantId={} dead={}",
                                tenantId, result.dead());
                    }
                    observedDeadBacklog += result.deadBacklog();
                } catch (RuntimeException exception) {
                    failed = true;
                    lastFailure.set(exception.getClass().getSimpleName());
                    lastFailureAt.set(now);
                    log.warn(
                            "Attention audit relay failed; tenantId={} errorType={}",
                            tenantId, exception.getClass().getSimpleName());
                }
            }
            if (page.size() < TENANT_PAGE_SIZE) break;
            cursor = page.get(page.size() - 1);
        }
        if (!failed) {
            deadEvents.set(observedDeadBacklog);
            lastSuccess.set(now);
            lastFailure.set(null);
            lastFailureAt.set(null);
        } else {
            long backlog = observedDeadBacklog;
            deadEvents.updateAndGet(current -> Math.max(current, backlog));
        }
    }

    private void runSafely() {
        if (!running.get()) return;
        try {
            runOnce(Instant.now());
        } catch (RuntimeException exception) {
            lastFailure.set(exception.getClass().getSimpleName());
            lastFailureAt.set(Instant.now());
            log.warn(
                    "Attention audit relay cycle failed; errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 90;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.unknown().withDetail("reason", "RELAY_DISABLED").build();
        }
        if (!transportConfigured) {
            return Health.unknown().withDetail("reason", "AUDIT_TRANSPORT_NOT_CONFIGURED").build();
        }
        if (!running.get()) {
            return Health.down().withDetail("reason", "RELAY_NOT_RUNNING").build();
        }
        Health.Builder health = deadEvents.get() > 0 || lastFailure.get() != null
                ? Health.down()
                : Health.up();
        health.withDetail("deadEvents", deadEvents.get());
        if (lastSuccess.get() != null) health.withDetail("lastSuccess", lastSuccess.get());
        if (lastFailure.get() != null) health.withDetail("lastFailure", lastFailure.get());
        if (lastFailureAt.get() != null) health.withDetail("lastFailureAt", lastFailureAt.get());
        return health.build();
    }
}
