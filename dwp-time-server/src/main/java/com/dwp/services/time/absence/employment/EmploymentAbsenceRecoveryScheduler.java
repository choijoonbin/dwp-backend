package com.dwp.services.time.absence.employment;

import java.util.UUID;

/** Non-blocking bounded worker queue; scheduling never confers owner authority. */
public interface EmploymentAbsenceRecoveryScheduler {
    boolean scheduleBaseline(long tenantId, UUID relationshipPublicId);

    boolean scheduleRefresh(long tenantId, UUID relationshipPublicId, String reason);
}
