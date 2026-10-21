package com.dwp.services.time.absence.employment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** No worker execution is possible before a separately admitted scheduler/broker binding. */
@Component
@ConditionalOnMissingBean(EmploymentAbsenceRecoveryScheduler.class)
final class FailClosedEmploymentAbsenceRecoveryScheduler
        implements EmploymentAbsenceRecoveryScheduler {
    @Override
    public boolean scheduleBaseline(long tenantId, UUID relationshipPublicId) {
        return false;
    }

    @Override
    public boolean scheduleRefresh(
            long tenantId,
            UUID relationshipPublicId,
            String reason) {
        return false;
    }
}
