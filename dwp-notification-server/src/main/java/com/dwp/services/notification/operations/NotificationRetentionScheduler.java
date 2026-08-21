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
        prefix = "dwp.notification.retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionScheduler.class);
    private static final int TENANT_PAGE_SIZE = 500;

    private final NotificationMaintenanceTenantRepository tenants;
    private final NotificationRetentionService retention;

    public NotificationRetentionScheduler(
            NotificationMaintenanceTenantRepository tenants,
            NotificationRetentionService retention) {
        this.tenants = tenants;
        this.retention = retention;
    }

    @Scheduled(
            fixedDelayString = "${dwp.notification.retention.fixed-delay:5m}",
            initialDelayString = "${dwp.notification.retention.initial-delay:1m}")
    public void run() {
        Instant now = Instant.now();
        long cursor = Long.MIN_VALUE;
        while (true) {
            List<Long> page = tenants.activeTenantIdsAfter(cursor, TENANT_PAGE_SIZE);
            for (Long tenantId : page) {
                try {
                    var result = retention.purgeTenant(tenantId, now);
                    if (result.deletedProjections() > 0 || result.deletedNotifications() > 0) {
                        log.info(
                                "Notification retention purge completed tenantId={} projections={} notifications={}",
                                tenantId,
                                result.deletedProjections(),
                                result.deletedNotifications());
                    }
                } catch (RuntimeException exception) {
                    log.warn(
                            "Notification retention purge failed tenantId={} errorType={}",
                            tenantId,
                            exception.getClass().getSimpleName());
                }
            }
            if (page.size() < TENANT_PAGE_SIZE) return;
            cursor = page.get(page.size() - 1);
        }
    }
}
