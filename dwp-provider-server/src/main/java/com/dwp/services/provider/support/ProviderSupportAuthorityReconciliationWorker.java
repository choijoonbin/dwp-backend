package com.dwp.services.provider.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically asks the database to reconcile support authority against its own clock. */
@Component
public class ProviderSupportAuthorityReconciliationWorker {

    private static final Logger log =
            LoggerFactory.getLogger(ProviderSupportAuthorityReconciliationWorker.class);

    private final ProviderSupportAuthorityReconciliationService service;
    private final boolean enabled;

    public ProviderSupportAuthorityReconciliationWorker(
            ProviderSupportAuthorityReconciliationService service,
            @Value("${dwp.provider.support-authority-reconciliation.enabled:true}")
                    boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString =
            "${dwp.provider.support-authority-reconciliation.delay-ms:5000}")
    public void pollSafely() {
        if (!enabled) {
            return;
        }
        try {
            service.reconcile();
        } catch (RuntimeException failure) {
            log.error("Support authority reconciliation pulse failed", failure);
        }
    }
}
