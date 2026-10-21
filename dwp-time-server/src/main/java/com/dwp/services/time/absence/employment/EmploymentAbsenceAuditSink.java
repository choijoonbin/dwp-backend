package com.dwp.services.time.absence.employment;

import java.util.Map;
import java.util.UUID;

/** Transaction-joining structured service audit boundary. */
public interface EmploymentAbsenceAuditSink {
    void append(
            long tenantId,
            UUID relationshipPublicId,
            UUID correlationId,
            String action,
            String outcome,
            Map<String, Object> evidence);
}
