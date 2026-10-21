package com.dwp.services.time.absence.employment;

import java.util.UUID;

/** Transaction-joining wake-up of the one terminal deferred gap event. */
public interface EmploymentAbsenceDeferredInboxActivator {

    void activateExact(long tenantId, UUID relationshipPublicId, long eventSequence);
}
