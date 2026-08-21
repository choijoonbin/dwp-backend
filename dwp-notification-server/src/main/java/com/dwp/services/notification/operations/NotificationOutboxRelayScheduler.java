package com.dwp.services.notification.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

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

    public NotificationOutboxRelayScheduler(
            NotificationMaintenanceTenantRepository tenants,
            NotificationOutboxRelayService relay) {
        this.tenants = tenants;
        this.relay = relay;
    }

    @Scheduled(
            fixedDelayString = "${dwp.notification.outbox.fixed-delay:1s}",
            initialDelayString = "${dwp.notification.outbox.initial-delay:5s}")
    public void run() {
        Instant now = Instant.now();
        long cursor = Long.MIN_VALUE;
        while (true) {
            List<Long> page = tenants.activeTenantIdsAfter(cursor, TENANT_PAGE_SIZE);
            for (Long tenantId : page) {
                try {
                    var result = relay.relayTenant(tenantId, now);
                    if (result.failed() > 0 || result.dead() > 0) {
                        log.warn(
                                "Notification outbox relay requires attention tenantId={} failed={} dead={}",
                                tenantId,
                                result.failed(),
                                result.dead());
                    }
                } catch (RuntimeException exception) {
                    log.warn(
                            "Notification outbox relay failed tenantId={} errorType={}",
                            tenantId,
                            exception.getClass().getSimpleName());
                }
            }
            if (page.size() < TENANT_PAGE_SIZE) return;
            cursor = page.get(page.size() - 1);
        }
    }
}
