package com.dwp.services.platform.workplace.workplacevisits;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class WorkplaceVisitRetentionService {
    private final WorkplaceVisitRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceVisitRetentionService(WorkplaceVisitRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceVisitRetentionService(WorkplaceVisitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public int purgeExpiredGuestReferences(long tenantId, int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Retention batch size must be between 1 and 500.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        int purged = repository.purgeExpiredGuestReferences(tenantId, now, batchSize);
        repository.audit(tenantId, null, 0, "workplace.visit.retention.purged", null,
                Map.of("purgedGuestReferences", purged, "boundedBatchSize", batchSize,
                        "cutoff", now.toString()), now);
        return purged;
    }
}
