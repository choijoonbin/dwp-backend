package com.dwp.services.space.operations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SpaceOperationsMaintenance {

    private final SpaceOperationsRepository repository;
    private final SpaceOperationsService service;
    private final String workerId;

    public SpaceOperationsMaintenance(
            SpaceOperationsRepository repository,
            SpaceOperationsService service,
            @Value("${dwp.service-instance:${HOSTNAME:local}}") String serviceInstance) {
        this.repository = repository;
        this.service = service;
        this.workerId = serviceInstance + ":space-entitlements:" + UUID.randomUUID();
    }

    @Scheduled(
            initialDelayString = "${dwp.space.reconciliation-initial-delay-ms:5000}",
            fixedDelayString = "${dwp.space.reconciliation-interval-ms:60000}")
    public void reconcile() {
        for (Long tenantId : repository.activeTenantIds()) {
            try {
                service.reconcileScheduled(tenantId);
            } catch (RuntimeException ignored) {
                // The failed run is persisted by the service for operator recovery.
            }
        }
    }

    @Scheduled(
            initialDelayString = "${dwp.space.entitlement-initial-delay-ms:3000}",
            fixedDelayString = "${dwp.space.entitlement-poll-interval-ms:2000}")
    public void deliverEntitlements() {
        service.deliver(50, workerId);
    }
}
