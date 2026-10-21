package com.dwp.services.time.absence.employment;

import java.util.UUID;

/** Independent service authority for explicit TIM-003 projection recovery. */
public interface EmploymentAbsenceProjectionRecoveryAuthority {

    boolean authorized(
            long tenantId,
            String actor,
            String capability,
            UUID correlationId);
}
