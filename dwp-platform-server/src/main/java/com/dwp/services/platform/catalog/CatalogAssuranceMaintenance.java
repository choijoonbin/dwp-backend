package com.dwp.services.platform.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CatalogAssuranceMaintenance {

    private static final Logger log = LoggerFactory.getLogger(CatalogAssuranceMaintenance.class);

    private final CatalogRepository repository;
    private final CatalogService service;

    public CatalogAssuranceMaintenance(CatalogRepository repository, CatalogService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(cron = "${dwp.platform.catalog.assurance-cron:0 37 2 * * *}")
    public void evaluateActiveTenants() {
        for (Long tenantId : repository.activeTenantIds()) {
            try {
                service.evaluateAssuranceSystem(tenantId);
            } catch (RuntimeException exception) {
                log.error("Catalog assurance evaluation failed for tenant {}", tenantId, exception);
            }
        }
    }
}
