package com.dwp.services.time.absence.employment;

import java.util.Optional;
import java.util.OptionalLong;
import java.time.Instant;
import java.util.UUID;

/** Transaction-joining V14 projection access with exact version-CAS semantics. */
public interface EmploymentAbsenceProjectionStore {

    /** Serializes projection reads and event/recovery decisions for one tenant relationship. */
    void lockTuple(long tenantId, UUID relationshipPublicId);

    Optional<EmploymentAbsenceProjection> find(long tenantId, UUID relationshipPublicId);

    /** A read that preserves the tuple identity/version when stored data cannot be mapped. */
    default EvaluationRead readForEvaluation(
            long tenantId,
            UUID relationshipPublicId) {
        return find(tenantId, relationshipPublicId)
                .<EvaluationRead>map(EvaluationRead.Available::new)
                .orElseGet(EvaluationRead.Missing::new);
    }

    /** Raw version-CAS quarantine used only when full row materialization is unsafe. */
    default CorruptionQuarantine quarantineCorruptExpectedVersion(
            long tenantId,
            UUID relationshipPublicId,
            long expectedProjectionVersion,
            Instant at) {
        EmploymentAbsenceProjection current = find(tenantId, relationshipPublicId)
                .orElseThrow(() -> new IllegalStateException("projection disappeared"));
        EmploymentAbsenceProjection stored = updateExpectedVersion(
                current.quarantineStoredIntegrityFailure(at),
                expectedProjectionVersion);
        return new CorruptionQuarantine(stored.projectionVersion(), true);
    }

    EmploymentAbsenceProjection insertExpectedZero(EmploymentAbsenceProjection candidate);

    EmploymentAbsenceProjection updateExpectedVersion(
            EmploymentAbsenceProjection candidate,
            long expectedProjectionVersion);

    boolean hasUnresolvedInbox(long tenantId, UUID relationshipPublicId);

    boolean hasBlockingInbox(long tenantId, UUID relationshipPublicId);

    /** Baseline authority is stricter than the read blocker: every inbox status counts. */
    boolean hasAnyInbox(long tenantId, UUID relationshipPublicId);

    /** Exact shared-ledger watermark; absence and zero are both baseline-eligible. */
    OptionalLong sharedOffset(long tenantId, UUID relationshipPublicId);

    sealed interface EvaluationRead {
        record Missing() implements EvaluationRead { }

        record Available(EmploymentAbsenceProjection projection)
                implements EvaluationRead {
            public Available {
                java.util.Objects.requireNonNull(projection, "projection");
            }
        }

        record Corrupt(long projectionVersion) implements EvaluationRead {
            public Corrupt {
                if (projectionVersion <= 0) {
                    throw new IllegalArgumentException("projectionVersion must be positive");
                }
            }
        }
    }

    record CorruptionQuarantine(long projectionVersion, boolean changed) {
        public CorruptionQuarantine {
            if (projectionVersion <= 0) {
                throw new IllegalArgumentException("projectionVersion must be positive");
            }
        }
    }
}
