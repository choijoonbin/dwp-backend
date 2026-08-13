package com.dwp.services.platform.preference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ManagedPreferenceMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ManagedPreferenceMaintenance.class);

    private final ManagedPreferenceService service;

    public ManagedPreferenceMaintenance(ManagedPreferenceService service) {
        this.service = service;
    }

    @Scheduled(cron = "${dwp.platform.preference-exception.expiry-cron:0 */5 * * * *}")
    public void expireRequests() {
        int expired = service.expireDueRequests();
        if (expired > 0) log.info("Expired {} managed preference exception request(s)", expired);
    }
}
