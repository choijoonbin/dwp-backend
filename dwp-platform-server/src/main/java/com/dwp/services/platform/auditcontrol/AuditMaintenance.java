package com.dwp.services.platform.auditcontrol;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class AuditMaintenance {

    private final AuditControlRepository repository;
    private final AuditIntegrityService integrityService;

    public AuditMaintenance(AuditControlRepository repository, AuditIntegrityService integrityService) {
        this.repository = repository;
        this.integrityService = integrityService;
    }

    @Scheduled(cron = "${dwp.platform.audit.integrity-cron:0 15 0 * * *}")
    public void createDailyCheckpoints() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        for (Long tenantId : repository.activeTenants()) integrityService.checkpoint(tenantId, yesterday);
    }

    @Scheduled(cron = "${dwp.platform.audit.retention-cron:0 10 2 * * *}")
    @Transactional
    public void applyRetention() {
        repository.applyRetention();
    }
}
