package com.dwp.services.platform.auditcontrol;

import java.time.Instant;

record EventCorrelationCriteria(
        Long tenantId,
        Instant from,
        Instant to,
        String domain,
        String classification,
        String query) { }
