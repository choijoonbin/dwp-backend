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
        prefix = "dwp.notification.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationCounterReconciliationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationCounterReconciliationScheduler.class);
    private static final int TENANT_PAGE_SIZE = 500;

    private final NotificationMaintenanceTenantRepository tenants;
    private final NotificationCounterReconciliationService reconciliation;

    public NotificationCounterReconciliationScheduler(
            NotificationMaintenanceTenantRepository tenants,
            NotificationCounterReconciliationService reconciliation) {
        this.tenants = tenants;
        this.reconciliation = reconciliation;
    }

    @Scheduled(
            fixedDelayString = "${dwp.notification.reconciliation.fixed-delay:5m}",
            initialDelayString = "${dwp.notification.reconciliation.initial-delay:2m}")
    public void run() {
        Instant now = Instant.now();
        long cursor = Long.MIN_VALUE;
        while (true) {
            List<Long> page = tenants.activeTenantIdsAfter(cursor, TENANT_PAGE_SIZE);
            for (Long tenantId : page) {
                try {
                    var result = reconciliation.reconcileTenant(tenantId, now);
                    if (result.detected() > 0) {
                        log.info(
                                "Notification counter reconciliation completed tenantId={} detected={} repaired={} moreLikely={}",
                                tenantId,
                                result.detected(),
                                result.repaired(),
                                result.moreLikely());
                    }
                } catch (RuntimeException exception) {
                    log.warn(
                            "Notification counter reconciliation failed tenantId={} errorType={}",
                            tenantId,
                            exception.getClass().getSimpleName());
                }
            }
            if (page.size() < TENANT_PAGE_SIZE) return;
            cursor = page.get(page.size() - 1);
        }
    }
}
