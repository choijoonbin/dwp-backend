package com.dwp.services.platform.workplace.workplacevisits;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.VisitState;

@Service
public class WorkplaceVisitMaintenanceService {
    private final WorkplaceVisitRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceVisitMaintenanceService(WorkplaceVisitRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceVisitMaintenanceService(WorkplaceVisitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public int markOverstays(long tenantId, int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Maintenance batch size must be between 1 and 500.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<UUID> changed = repository.markOverstays(tenantId, now, batchSize);
        changed.forEach(visitId -> repository.timeline(tenantId, visitId, 0,
                "VISIT_OVERSTAY_DETECTED", VisitState.OVERSTAY, "CHECKOUT_REQUIRED", now));
        repository.audit(tenantId, null, 0, "workplace.visit.overstay.scanned", null,
                Map.of("overstayCount", changed.size(), "boundedBatchSize", batchSize), now);
        return changed.size();
    }
}
