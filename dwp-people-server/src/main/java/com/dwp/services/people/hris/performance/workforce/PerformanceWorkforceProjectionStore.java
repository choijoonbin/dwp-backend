package com.dwp.services.people.hris.performance.workforce;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface PerformanceWorkforceProjectionStore {

    PerformanceWorkforceProjection.Head replaceFromOwner(
            PerformanceWorkforceProjection.Snapshot snapshot,
            ReplayFence replayFence);

    ReplayFence beginReplay(long tenantId, UUID replayEventId);

    Optional<PerformanceWorkforceProjection.Head> head(long tenantId);

    InvalidationResult invalidate(InvalidationEvent event);

    enum SubjectType {
        WORKER,
        EMPLOYMENT,
        ASSIGNMENT,
        ORGANIZATION
    }

    enum InvalidationOutcome {
        STALE,
        BLOCKED_DEPENDENCY_GAP,
        IGNORED_OLDER_REVISION,
        REPLAYED,
        QUARANTINED
    }

    record ReplayFence(
            long tenantId,
            UUID replayEventId,
            Set<UUID> coveredEventIds) {

        public ReplayFence {
            if (tenantId <= 0) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            Objects.requireNonNull(replayEventId, "replayEventId must not be null");
            coveredEventIds = Set.copyOf(Objects.requireNonNull(
                    coveredEventIds, "coveredEventIds must not be null"));
            if (!coveredEventIds.contains(replayEventId)) {
                throw new IllegalArgumentException(
                        "coveredEventIds must contain replayEventId");
            }
        }
    }

    record InvalidationEvent(
            long tenantId,
            UUID eventId,
            String eventType,
            SubjectType subjectType,
            UUID subjectPublicId,
            Long aggregateRevision,
            LocalDate effectiveDate,
            String payloadHash) {

        public InvalidationEvent {
            if (tenantId <= 0) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            Objects.requireNonNull(eventId, "eventId must not be null");
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("eventType must be nonblank");
            }
            Objects.requireNonNull(subjectType, "subjectType must not be null");
            Objects.requireNonNull(subjectPublicId, "subjectPublicId must not be null");
            Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
            if (subjectType == SubjectType.ORGANIZATION) {
                if (aggregateRevision != null) {
                    throw new IllegalArgumentException(
                            "OrganizationChanged.v1 has an opaque revision UUID");
                }
            } else if (aggregateRevision == null || aggregateRevision <= 0) {
                throw new IllegalArgumentException(
                        "aggregateRevision must be positive for sequenced events");
            }
            if (payloadHash == null
                    || !PerformanceWorkforceProjection.SHA_256.matcher(payloadHash).matches()) {
                throw new IllegalArgumentException(
                        "payloadHash must be a lowercase SHA-256 digest");
            }
        }
    }

    record InvalidationResult(
            UUID eventId,
            InvalidationOutcome outcome,
            boolean duplicate,
            PerformanceWorkforceProjection.State projectionState,
            Long requiredAggregateRevision,
            Long observedAggregateRevision) {

        public InvalidationResult {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(projectionState, "projectionState must not be null");
            boolean gap = outcome == InvalidationOutcome.BLOCKED_DEPENDENCY_GAP;
            if (gap != (requiredAggregateRevision != null
                    && observedAggregateRevision != null)) {
                throw new IllegalArgumentException(
                        "gap revisions must be present exactly for a gap outcome");
            }
        }
    }
}
