package com.dwp.services.people.hris.separation.hrm013;

import java.time.Instant;
import java.util.UUID;

/** Typed in-process boundary. HRIS-PER must never self-HTTP into People. */
@FunctionalInterface
public interface SeparationReconciliationPort {
    Result reconcile(Command command);

    record Command(
            long tenantId, UUID casePublicId, UUID consumerEventId,
            String sourceWorkloadId, String sourcePayloadSha256,
            UUID correlationId, Instant occurredAt) {
        public Command {
            if (tenantId < 1) throw new IllegalArgumentException("tenantId");
            java.util.Objects.requireNonNull(casePublicId, "casePublicId");
            java.util.Objects.requireNonNull(consumerEventId, "consumerEventId");
            if (sourceWorkloadId == null || sourceWorkloadId.isBlank()) {
                throw new IllegalArgumentException("sourceWorkloadId");
            }
            if (sourcePayloadSha256 == null
                    || !sourcePayloadSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sourcePayloadSha256");
            }
            java.util.Objects.requireNonNull(correlationId, "correlationId");
            java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record Result(
            UUID consumerEventId, UUID casePublicId, String state,
            long ownerRevision, UUID correlationId) {
        public Result {
            java.util.Objects.requireNonNull(consumerEventId, "consumerEventId");
            java.util.Objects.requireNonNull(casePublicId, "casePublicId");
            if (!java.util.Set.of("ADMITTED", "REPLAYED", "QUARANTINED").contains(state)) {
                throw new IllegalArgumentException("state");
            }
            if (ownerRevision < 1) throw new IllegalArgumentException("ownerRevision");
            java.util.Objects.requireNonNull(correlationId, "correlationId");
        }
    }
}
