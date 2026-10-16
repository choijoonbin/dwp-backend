package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Supplies only the closed, non-narrative values allowed on Performance Home. */
public interface PerformanceHomeProjectionPort {

    Snapshot load(PerformanceHomeAuthorityPort.AuthorityDecision authority);

    record Snapshot(
            GoalProgress goalProgress,
            ReviewStatus reviewStatus,
            int pendingActionCount,
            long sourceVersion,
            Instant asOf,
            Instant generatedAt,
            Instant freshUntil,
            Instant staleAfter,
            HrisHomeContributionMaterializationV1.State state,
            List<SourceFailure> partialFailures) {
        public Snapshot {
            Objects.requireNonNull(goalProgress, "goalProgress");
            Objects.requireNonNull(reviewStatus, "reviewStatus");
            if (pendingActionCount < 0 || pendingActionCount > 999 || sourceVersion < 1) {
                throw new IllegalArgumentException("Performance Home projection range is invalid.");
            }
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(generatedAt, "generatedAt");
            Objects.requireNonNull(freshUntil, "freshUntil");
            Objects.requireNonNull(staleAfter, "staleAfter");
            Objects.requireNonNull(state, "state");
            if (asOf.isAfter(generatedAt) || generatedAt.isAfter(freshUntil)
                    || freshUntil.isAfter(staleAfter)) {
                throw new IllegalArgumentException("Performance Home freshness order is invalid.");
            }
            partialFailures = partialFailures == null ? List.of() : List.copyOf(partialFailures);
            if ((state == HrisHomeContributionMaterializationV1.State.FRESH
                    || state == HrisHomeContributionMaterializationV1.State.STALE)
                    && !partialFailures.isEmpty()) {
                throw new IllegalArgumentException("Fresh or stale data cannot carry failures.");
            }
            if ((state == HrisHomeContributionMaterializationV1.State.DEGRADED
                    || state == HrisHomeContributionMaterializationV1.State.UNAVAILABLE)
                    && partialFailures.isEmpty()) {
                throw new IllegalArgumentException("Unavailable data requires failure evidence.");
            }
        }
    }

    enum GoalProgress {
        NOT_STARTED,
        ON_TRACK,
        AT_RISK,
        COMPLETE,
        NOT_APPLICABLE
    }

    enum ReviewStatus {
        NOT_STARTED,
        SELF_REVIEW,
        MANAGER_REVIEW,
        CALIBRATION,
        RELEASED,
        NOT_APPLICABLE
    }

    record SourceFailure(
            HrisHomeContributionMaterializationV1.FailureCode code,
            boolean retryable,
            UUID correlationId,
            Instant occurredAt) {
        public SourceFailure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
