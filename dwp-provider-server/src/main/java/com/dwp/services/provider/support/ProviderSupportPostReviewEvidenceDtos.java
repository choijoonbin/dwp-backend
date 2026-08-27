package com.dwp.services.provider.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Minimal, server-bound evidence exposed to an independent post-access reviewer. */
public final class ProviderSupportPostReviewEvidenceDtos {

    private ProviderSupportPostReviewEvidenceDtos() {
    }

    public record Evidence(
            UUID supportAccessRequestId,
            UUID supportSessionId,
            UUID tenantId,
            String sessionLifecycleState,
            Instant evidenceFrom,
            Instant evidenceThrough,
            List<String> grantedScopes,
            List<String> observedScopes,
            long totalEventCount,
            long actualUseCount,
            long deniedAttemptCount,
            boolean evidenceComplete,
            boolean displayTruncated,
            boolean noUseConfirmed,
            String readiness,
            List<String> anomalies,
            List<Event> events) {
    }

    public record Event(
            UUID auditEventId,
            Instant occurredAt,
            String decision,
            String method,
            String routeTemplate,
            String scope,
            String outcome,
            String reasonCode,
            String correlationId) {
    }
}
