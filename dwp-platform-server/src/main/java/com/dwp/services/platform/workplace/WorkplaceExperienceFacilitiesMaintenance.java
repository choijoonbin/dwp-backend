package com.dwp.services.platform.workplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class WorkplaceExperienceFacilitiesMaintenance {
    private static final Logger log=LoggerFactory.getLogger(WorkplaceExperienceFacilitiesMaintenance.class);
    private final WorkplaceExperienceFacilitiesRetention retention;
    private final boolean enabled;
    private final int batchSize;
    WorkplaceExperienceFacilitiesMaintenance(WorkplaceExperienceFacilitiesRetention retention,
            @Value("${dwp.platform.workplace.privacy-maintenance.enabled:true}") boolean enabled,
            @Value("${dwp.platform.workplace.privacy-maintenance.batch-size:500}") int batchSize) {
        if(batchSize<1 || batchSize>5000) throw new IllegalArgumentException("Invalid facility retention batch size");
        this.retention=retention;this.enabled=enabled;this.batchSize=batchSize;
    }
    @Scheduled(cron="${dwp.platform.workplace.privacy-maintenance.cron:0 23 3 * * *}")
    void purgeExpiredRecords() {
        if(!enabled)return;
        try {var result=retention.purge(batchSize);
            if(result.requests()+result.closures()>0) log.info("Purged {} retained facility requests and {} retained closures",result.requests(),result.closures());
        } catch(RuntimeException failure) {log.error("Native Workplace facility retention failed",failure);}
    }
}
