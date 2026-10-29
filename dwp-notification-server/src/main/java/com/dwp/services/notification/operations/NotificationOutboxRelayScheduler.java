package com.dwp.services.notification.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(
        prefix = "dwp.notification.outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationOutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxRelayScheduler.class);
    private static final int TENANT_PAGE_SIZE = 500;

    private final NotificationMaintenanceTenantRepository tenants;
    private final NotificationOutboxRelayService relay;
    private final NotificationOutboxOperationalMonitor monitor;

    public NotificationOutboxRelayScheduler(
            NotificationMaintenanceTenantRepository tenants,
            NotificationOutboxRelayService relay,
            NotificationOutboxOperationalMonitor monitor) {
        this.tenants = tenants;
        this.relay = relay;
        this.monitor = monitor;
    }

    @Scheduled(
            fixedDelayString = "${dwp.notification.outbox.fixed-delay:1s}",
            initialDelayString = "${dwp.notification.outbox.initial-delay:5s}",
            scheduler = "notificationOutboxTaskScheduler")
    public void run() {
        Instant now = Instant.now();
        long cursor = Long.MIN_VALUE;
        AtomicLong backlog = new AtomicLong();
        AtomicLong retries = new AtomicLong();
        AtomicLong dead = new AtomicLong();
        AtomicLong leaseLost = new AtomicLong();
        AtomicReference<Instant> oldest = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            while (true) {
                List<Long> page = tenants.activeTenantIdsAfter(cursor, TENANT_PAGE_SIZE);
                for (Long tenantId : page) {
                    try {
                        var result = relay.relayTenant(tenantId, now);
                        monitor.recordRelay(result);
                        leaseLost.addAndGet(result.leaseLost());
                        if (result.failed() > 0) {
                            failure.compareAndSet(null, "EVENT_DELIVERY_FAILED");
                        }
                        if (result.failed() > 0 || result.dead() > 0) {
                            log.warn(
                                    "Notification outbox relay requires attention tenantId={} failed={} dead={}",
                                    tenantId,
                                    result.failed(),
                                    result.dead());
                        }
                    } catch (RuntimeException exception) {
                        failure.compareAndSet(null, exception.getClass().getSimpleName());
                        log.warn(
                                "Notification outbox relay failed tenantId={} errorType={}",
                                tenantId,
                                exception.getClass().getSimpleName());
                    }
                    try {
                        var status = relay.statusTenant(tenantId);
                        backlog.addAndGet(status.backlog());
                        retries.addAndGet(status.retries());
                        dead.addAndGet(status.dead());
                        if (status.oldestCreatedAt() != null) {
                            oldest.updateAndGet(current -> current == null
                                    || status.oldestCreatedAt().isBefore(current)
                                    ? status.oldestCreatedAt() : current);
                        }
                    } catch (RuntimeException exception) {
                        failure.compareAndSet(null, exception.getClass().getSimpleName());
                        log.warn(
                                "Notification outbox status probe failed tenantId={} errorType={}",
                                tenantId,
                                exception.getClass().getSimpleName());
                    }
                }
                if (page.size() < TENANT_PAGE_SIZE) break;
                cursor = page.get(page.size() - 1);
            }
        } catch (RuntimeException exception) {
            failure.compareAndSet(null, exception.getClass().getSimpleName());
            log.warn(
                    "Notification outbox relay cycle failed errorType={}",
                    exception.getClass().getSimpleName());
        }
        monitor.recordCycle(
                new NotificationOutboxOperationalMonitor.OutboxSnapshot(
                        backlog.get(), retries.get(), dead.get(), leaseLost.get(), oldest.get()),
                failure.get() == null,
                now,
                failure.get());
    }
}
