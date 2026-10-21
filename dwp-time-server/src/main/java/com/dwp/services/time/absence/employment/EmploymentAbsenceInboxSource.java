package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventEnvelope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only bounded source of exact TIM-003 unresolved shared-inbox rows. */
public interface EmploymentAbsenceInboxSource {
    List<InboxCandidate> unresolved(int maximumRows);

    record InboxCandidate(
            DomainEventEnvelope event,
            UUID eventId,
            String payloadSha256,
            long tenantId,
            String aggregateId,
            String status,
            String lockToken,
            Instant lockedUntil,
            String lastError) {
        public InboxCandidate {
            Objects.requireNonNull(eventId, "eventId");
            EmploymentAbsenceContract.digest(payloadSha256, "payloadSha256");
            EmploymentAbsenceContract.positive(tenantId, "tenantId");
            aggregateId = aggregateId == null ? "" : aggregateId;
            EmploymentAbsenceContract.bounded(status, 32, "status");
        }

        boolean activeProcessing(Instant now) {
            return "PROCESSING".equals(status)
                    && lockToken != null && !lockToken.isBlank()
                    && lockedUntil != null && lockedUntil.isAfter(now);
        }

        boolean malformed() {
            return event == null;
        }
    }
}
