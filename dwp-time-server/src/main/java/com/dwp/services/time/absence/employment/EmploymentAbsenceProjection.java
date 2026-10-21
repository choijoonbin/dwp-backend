package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.FreshnessState;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.RecoveryOutcome;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable operational view of the single V14 relationship projection row. */
public record EmploymentAbsenceProjection(
        long tenantId,
        UUID relationshipPublicId,
        FreshnessState freshness,
        long lastObservedEventSequence,
        long requiredRelationshipRevision,
        long sharedOffsetSequence,
        Long gapExpectedSequence,
        Xcon027EmploymentAbsenceSnapshotV1 ownerSnapshot,
        StoredOwnerEvidence ownerEvidence,
        String ownerBusinessTimeZone,
        long ownerMinimumRelationshipRevision,
        InvalidationEvidence lastInvalidation,
        RecoveryOutcome recoveryOutcome,
        String lastRecoveryErrorCode,
        Instant lastRecoveryAt,
        long projectionVersion,
        Instant createdAt,
        Instant updatedAt) {

    public EmploymentAbsenceProjection {
        EmploymentAbsenceContract.positive(tenantId, "tenantId");
        Objects.requireNonNull(relationshipPublicId, "relationshipPublicId");
        Objects.requireNonNull(freshness, "freshness");
        EmploymentAbsenceContract.nonnegative(
                lastObservedEventSequence, "lastObservedEventSequence");
        EmploymentAbsenceContract.nonnegative(
                requiredRelationshipRevision, "requiredRelationshipRevision");
        EmploymentAbsenceContract.nonnegative(sharedOffsetSequence, "sharedOffsetSequence");
        if (requiredRelationshipRevision < lastObservedEventSequence) {
            throw new IllegalArgumentException("required revision regressed");
        }
        if (projectionVersion < 0) {
            throw new IllegalArgumentException("projectionVersion is negative");
        }
        if ((lastObservedEventSequence == 0) != (lastInvalidation == null)) {
            throw new IllegalArgumentException("invalidation bundle is not atomic");
        }
        if (lastInvalidation != null
                && lastInvalidation.eventSequence() != lastObservedEventSequence) {
            throw new IllegalArgumentException("invalidation sequence differs");
        }
        if (freshness == FreshnessState.GAP) {
            if (gapExpectedSequence == null
                    || gapExpectedSequence != sharedOffsetSequence + 1
                    || lastObservedEventSequence <= gapExpectedSequence) {
                throw new IllegalArgumentException("GAP invariant failed");
            }
        } else if (gapExpectedSequence != null) {
            throw new IllegalArgumentException("only GAP has expected sequence");
        }
        boolean ownerAbsent = ownerSnapshot == null;
        if (ownerAbsent != (ownerEvidence == null)
                || ownerAbsent != (ownerBusinessTimeZone == null)) {
            throw new IllegalArgumentException("owner bundle is not atomic");
        }
        if (ownerAbsent && ownerMinimumRelationshipRevision != 0) {
            throw new IllegalArgumentException("absent owner has minimum revision");
        }
        if (!ownerAbsent) {
            EmploymentAbsenceContract.canonicalZone(ownerBusinessTimeZone);
            EmploymentAbsenceContract.nonnegative(ownerMinimumRelationshipRevision,
                    "ownerMinimumRelationshipRevision");
            if (!ownerSnapshot.relationshipPublicId().equals(relationshipPublicId)
                    || ownerSnapshot.relationshipRevision()
                            < ownerMinimumRelationshipRevision
                    || ownerEvidence.tenantId() != tenantId
                    || !ownerEvidence.businessTimeZone().equals(ownerBusinessTimeZone)) {
                throw new IllegalArgumentException("owner bundle identity differs");
            }
        }
        Objects.requireNonNull(recoveryOutcome, "recoveryOutcome");
        validateStatePair(freshness, recoveryOutcome);
        if (recoveryOutcome == RecoveryOutcome.NOT_ATTEMPTED) {
            if (lastRecoveryAt != null || lastRecoveryErrorCode != null) {
                throw new IllegalArgumentException("NOT_ATTEMPTED carries recovery evidence");
            }
        } else {
            Objects.requireNonNull(lastRecoveryAt, "lastRecoveryAt");
            boolean failed = recoveryOutcome == RecoveryOutcome.RETRYABLE_FAILURE
                    || recoveryOutcome == RecoveryOutcome.PERMANENT_FAILURE;
            if (failed == (lastRecoveryErrorCode == null)) {
                throw new IllegalArgumentException("recovery error evidence differs");
            }
            if (lastRecoveryErrorCode != null) {
                EmploymentAbsenceContract.code(lastRecoveryErrorCode,
                        "lastRecoveryErrorCode");
                if (lastRecoveryErrorCode.length() > 80) {
                    throw new IllegalArgumentException(
                            "lastRecoveryErrorCode exceeds V14 canonical bound");
                }
            }
        }
        if ((freshness == FreshnessState.CURRENT || freshness == FreshnessState.STALE)
                && (ownerAbsent
                || sharedOffsetSequence < requiredRelationshipRevision
                || ownerSnapshot.relationshipRevision() < sharedOffsetSequence)) {
            throw new IllegalArgumentException("current/stale coverage invariant failed");
        }
        if (createdAt != null && updatedAt != null && updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt precedes createdAt");
        }
    }

    public static EmploymentAbsenceProjection baselineClaim(
            long tenantId,
            UUID relationshipPublicId,
            Instant claimedAt) {
        return new EmploymentAbsenceProjection(
                tenantId, relationshipPublicId, FreshnessState.REFETCH_PENDING,
                0, 0, 0, null, null, null, null, 0, null,
                RecoveryOutcome.PENDING, null, claimedAt, 0, null, null);
    }

    public static EmploymentAbsenceProjection blocker(
            long tenantId,
            UUID relationshipPublicId,
            String reason,
            Instant at) {
        return new EmploymentAbsenceProjection(
                tenantId, relationshipPublicId, FreshnessState.QUARANTINED,
                0, 0, 0, null, null, null, null, 0, null,
                RecoveryOutcome.PERMANENT_FAILURE, reason, at, 0, null, null);
    }

    public EmploymentAbsenceProjection observe(
            InvalidationEvidence incoming,
            long currentSharedOffset,
            FreshnessState next,
            RecoveryOutcome outcome,
            Instant at) {
        long observed = Math.max(lastObservedEventSequence, incoming.eventSequence());
        InvalidationEvidence evidence = incoming.eventSequence() > lastObservedEventSequence
                ? incoming : lastInvalidation;
        long required = Math.max(requiredRelationshipRevision, incoming.eventSequence());
        Long gap = next == FreshnessState.GAP ? currentSharedOffset + 1 : null;
        return copy(next, observed, required, currentSharedOffset, gap,
                ownerSnapshot, ownerEvidence, ownerBusinessTimeZone,
                ownerMinimumRelationshipRevision, evidence, outcome, null,
                outcome == RecoveryOutcome.NOT_ATTEMPTED ? null : at);
    }

    public EmploymentAbsenceProjection recovered(
            Xcon027EmploymentAbsenceSnapshotV1 snapshot,
            EmploymentAbsenceContract.ResponseEvidence evidence,
            String businessTimeZone,
            long minimumRevision,
            long exactSharedOffset,
            Instant at) {
        if (snapshot.relationshipRevision() < requiredRelationshipRevision
                || snapshot.relationshipRevision() < minimumRevision) {
            throw new IllegalArgumentException("owner recovery regressed");
        }
        if (exactSharedOffset < requiredRelationshipRevision) {
            throw new IllegalArgumentException("recovery offset does not cover required revision");
        }
        return copy(FreshnessState.CURRENT, lastObservedEventSequence,
                requiredRelationshipRevision, exactSharedOffset, null,
                snapshot, StoredOwnerEvidence.from(evidence), businessTimeZone,
                minimumRevision,
                lastInvalidation, RecoveryOutcome.SUCCEEDED, null, at);
    }

    public EmploymentAbsenceProjection covered(
            InvalidationEvidence incoming,
            long exactIncomingSequence,
            Instant at) {
        if (ownerSnapshot == null || freshness != FreshnessState.CURRENT
                || ownerSnapshot.relationshipRevision() < exactIncomingSequence
                || exactIncomingSequence != sharedOffsetSequence + 1) {
            throw new IllegalArgumentException("covered fast path is not eligible");
        }
        return observe(incoming, exactIncomingSequence,
                FreshnessState.CURRENT, RecoveryOutcome.SUCCEEDED, at);
    }

    public EmploymentAbsenceProjection gapBackfill(
            long exactIncomingSequence,
            Instant at) {
        if (freshness != FreshnessState.GAP
                || exactIncomingSequence != sharedOffsetSequence + 1
                || exactIncomingSequence >= lastObservedEventSequence) {
            throw new IllegalArgumentException("gap backfill is not eligible");
        }
        long nextExpected = exactIncomingSequence + 1;
        boolean terminalPredecessor = nextExpected == lastObservedEventSequence;
        return copy(terminalPredecessor
                        ? FreshnessState.REFETCH_PENDING : FreshnessState.GAP,
                lastObservedEventSequence, requiredRelationshipRevision,
                exactIncomingSequence,
                terminalPredecessor ? null : nextExpected,
                ownerSnapshot, ownerEvidence, ownerBusinessTimeZone,
                ownerMinimumRelationshipRevision, lastInvalidation,
                terminalPredecessor ? RecoveryOutcome.PENDING : recoveryOutcome,
                terminalPredecessor ? null : lastRecoveryErrorCode,
                terminalPredecessor ? at : lastRecoveryAt);
    }

    /**
     * Records monotonic event evidence without ever releasing an existing quarantine.
     * The offset may either remain unchanged for a deferred gap observation or advance
     * by exactly the incoming contiguous sequence after the shared inbox completes it.
     */
    public EmploymentAbsenceProjection observeWhileQuarantined(
            InvalidationEvidence incoming,
            long exactSharedOffset) {
        if (freshness != FreshnessState.QUARANTINED
                || recoveryOutcome != RecoveryOutcome.PERMANENT_FAILURE
                || lastRecoveryErrorCode == null
                || exactSharedOffset < sharedOffsetSequence
                || exactSharedOffset > sharedOffsetSequence + 1
                || (exactSharedOffset > sharedOffsetSequence
                    && exactSharedOffset != incoming.eventSequence())) {
            throw new IllegalArgumentException(
                    "quarantined event observation is not eligible");
        }
        long observed = Math.max(lastObservedEventSequence, incoming.eventSequence());
        InvalidationEvidence evidence = incoming.eventSequence() > lastObservedEventSequence
                ? incoming : lastInvalidation;
        long required = Math.max(requiredRelationshipRevision, incoming.eventSequence());
        return copy(FreshnessState.QUARANTINED, observed, required,
                exactSharedOffset, null, ownerSnapshot, ownerEvidence,
                ownerBusinessTimeZone, ownerMinimumRelationshipRevision,
                evidence, RecoveryOutcome.PERMANENT_FAILURE,
                lastRecoveryErrorCode, lastRecoveryAt);
    }

    public EmploymentAbsenceProjection failed(
            EmploymentAbsenceContract.OwnerFailure failure,
            boolean gapMode,
            Instant at) {
        boolean permanent = failure.kind()
                == EmploymentAbsenceContract.OwnerFailureKind.PERMANENT;
        return copy(permanent ? FreshnessState.QUARANTINED
                        : gapMode ? FreshnessState.GAP : FreshnessState.REFETCH_PENDING,
                lastObservedEventSequence, requiredRelationshipRevision,
                sharedOffsetSequence, gapMode && !permanent
                        ? sharedOffsetSequence + 1 : null,
                ownerSnapshot, ownerEvidence, ownerBusinessTimeZone,
                ownerMinimumRelationshipRevision, lastInvalidation,
                permanent ? RecoveryOutcome.PERMANENT_FAILURE
                        : RecoveryOutcome.RETRYABLE_FAILURE,
                failure.code(), at);
    }

    public EmploymentAbsenceProjection claimRecovery(boolean gapMode, Instant at) {
        if (freshness == FreshnessState.QUARANTINED) {
            throw new IllegalArgumentException("quarantine cannot self-release");
        }
        if (lastRecoveryAt != null && lastRecoveryAt.plusSeconds(30).isAfter(at)
                && recoveryOutcome == RecoveryOutcome.PENDING) {
            throw new IllegalStateException("recovery claim is still active");
        }
        if (recoveryOutcome == RecoveryOutcome.RETRYABLE_FAILURE
                && lastRecoveryAt != null && lastRecoveryAt.plusSeconds(15).isAfter(at)) {
            throw new IllegalStateException("recovery backoff is active");
        }
        FreshnessState next = gapMode ? FreshnessState.GAP
                : FreshnessState.REFETCH_PENDING;
        Long gap = gapMode ? sharedOffsetSequence + 1 : null;
        return copy(next, lastObservedEventSequence,
                requiredRelationshipRevision, sharedOffsetSequence, gap,
                ownerSnapshot, ownerEvidence, ownerBusinessTimeZone,
                ownerMinimumRelationshipRevision, lastInvalidation,
                RecoveryOutcome.PENDING, null, at);
    }

    public boolean isOwnerBaselinePlaceholder() {
        return freshness == FreshnessState.REFETCH_PENDING
                && (recoveryOutcome == RecoveryOutcome.PENDING
                    || recoveryOutcome == RecoveryOutcome.RETRYABLE_FAILURE)
                && lastObservedEventSequence == 0
                && requiredRelationshipRevision == 0
                && sharedOffsetSequence == 0
                && lastInvalidation == null
                && ownerSnapshot == null;
    }

    /** Explicit operator release; callers must enforce capability, allowlist and CAS. */
    public EmploymentAbsenceProjection releaseQuarantine(Instant at) {
        if (freshness != FreshnessState.QUARANTINED
                || recoveryOutcome != RecoveryOutcome.PERMANENT_FAILURE
                || lastRecoveryErrorCode == null) {
            throw new IllegalStateException("projection is not releasable quarantine");
        }
        return copy(FreshnessState.REFETCH_PENDING,
                lastObservedEventSequence, requiredRelationshipRevision,
                sharedOffsetSequence, null, ownerSnapshot, ownerEvidence,
                ownerBusinessTimeZone, ownerMinimumRelationshipRevision,
                lastInvalidation, RecoveryOutcome.PENDING, null, at);
    }

    /** Drops only untrusted owner material while retaining ledger/event evidence. */
    public EmploymentAbsenceProjection quarantineStoredIntegrityFailure(Instant at) {
        return copy(FreshnessState.QUARANTINED,
                lastObservedEventSequence, requiredRelationshipRevision,
                sharedOffsetSequence, null, null, null, null, 0,
                lastInvalidation, RecoveryOutcome.PERMANENT_FAILURE,
                "STORED_PROJECTION_INTEGRITY_FAILURE", at);
    }

    public EmploymentAbsenceProjection stale() {
        if (ownerSnapshot == null) return this;
        return copy(FreshnessState.STALE, lastObservedEventSequence,
                requiredRelationshipRevision, sharedOffsetSequence, null,
                ownerSnapshot, ownerEvidence, ownerBusinessTimeZone,
                ownerMinimumRelationshipRevision, lastInvalidation,
                RecoveryOutcome.SUCCEEDED, null, lastRecoveryAt);
    }

    public boolean coveredAndReadable(
            LocalDate requestedDate,
            Instant now,
            EmploymentAbsenceContract.OwnerGrant grant,
            EmploymentAbsenceContract.TenantContext context,
            boolean unresolvedInbox) {
        return !unresolvedInbox
                && freshness == FreshnessState.CURRENT
                && ownerSnapshot != null
                && ownerEvidence != null
                && ownerSnapshot.validUntil().isAfter(now)
                && ownerSnapshot.effectiveOn().equals(requestedDate)
                && requestedDate.equals(context.currentDate(now))
                && context.matches(grant)
                && ownerEvidence.tenantGrantRevision() == grant.tenantGrantRevision()
                && ownerEvidence.authorizationRevision() == grant.authorizationRevision()
                && ownerEvidence.fieldPolicyRevision() == grant.fieldPolicyRevision()
                && ownerEvidence.businessTimeZone().equals(context.businessTimeZone())
                && ownerSnapshot.relationshipRevision() >= sharedOffsetSequence
                && sharedOffsetSequence >= requiredRelationshipRevision;
    }

    private EmploymentAbsenceProjection copy(
            FreshnessState nextFreshness,
            long nextObserved,
            long nextRequired,
            long nextOffset,
            Long nextGap,
            Xcon027EmploymentAbsenceSnapshotV1 nextSnapshot,
            StoredOwnerEvidence nextEvidence,
            String nextZone,
            long nextMinimum,
            InvalidationEvidence nextInvalidation,
            RecoveryOutcome nextOutcome,
            String nextError,
            Instant nextRecoveryAt) {
        return new EmploymentAbsenceProjection(
                tenantId, relationshipPublicId, nextFreshness, nextObserved,
                nextRequired, nextOffset, nextGap, nextSnapshot, nextEvidence,
                nextZone, nextMinimum, nextInvalidation, nextOutcome, nextError,
                nextRecoveryAt, projectionVersion, createdAt, updatedAt);
    }

    private static void validateStatePair(
            FreshnessState freshness,
            RecoveryOutcome outcome) {
        boolean allowed = switch (freshness) {
            case CURRENT, STALE -> outcome == RecoveryOutcome.SUCCEEDED;
            case REFETCH_PENDING -> outcome == RecoveryOutcome.PENDING
                    || outcome == RecoveryOutcome.RETRYABLE_FAILURE;
            case GAP -> outcome == RecoveryOutcome.NOT_ATTEMPTED
                    || outcome == RecoveryOutcome.PENDING
                    || outcome == RecoveryOutcome.RETRYABLE_FAILURE;
            case QUARANTINED -> outcome == RecoveryOutcome.PERMANENT_FAILURE;
        };
        if (!allowed) throw new IllegalArgumentException("closed state pair rejected");
    }

    /** Only evidence columns actually persisted by V14; no fabricated signature/correlation. */
    public record StoredOwnerEvidence(
            long tenantId,
            long tenantGrantRevision,
            String businessTimeZone,
            String purposeCode,
            String projectionId,
            long authorizationRevision,
            long fieldPolicyRevision,
            Instant issuedAt,
            Instant asOf,
            Instant validUntil,
            String scopeDigest,
            String contentSha256,
            String evidenceDigest,
            String keyId) {
        public StoredOwnerEvidence {
            EmploymentAbsenceContract.positive(tenantId, "ownerEvidence.tenantId");
            EmploymentAbsenceContract.positive(
                    tenantGrantRevision, "ownerEvidence.tenantGrantRevision");
            EmploymentAbsenceContract.canonicalZone(businessTimeZone);
            EmploymentAbsenceContract.exact(
                    purposeCode, EmploymentAbsenceContract.PURPOSE,
                    "ownerEvidence.purposeCode");
            EmploymentAbsenceContract.exact(
                    projectionId, EmploymentAbsenceContract.PROJECTION,
                    "ownerEvidence.projectionId");
            EmploymentAbsenceContract.positive(
                    authorizationRevision, "ownerEvidence.authorizationRevision");
            EmploymentAbsenceContract.positive(
                    fieldPolicyRevision, "ownerEvidence.fieldPolicyRevision");
            Objects.requireNonNull(issuedAt, "ownerEvidence.issuedAt");
            Objects.requireNonNull(asOf, "ownerEvidence.asOf");
            Objects.requireNonNull(validUntil, "ownerEvidence.validUntil");
            if (!issuedAt.equals(asOf)) {
                throw new IllegalArgumentException("stored owner issuedAt differs");
            }
            EmploymentAbsenceContract.digest(scopeDigest, "ownerEvidence.scopeDigest");
            EmploymentAbsenceContract.digest(
                    contentSha256, "ownerEvidence.contentSha256");
            EmploymentAbsenceContract.digest(
                    evidenceDigest, "ownerEvidence.evidenceDigest");
            EmploymentAbsenceContract.bounded(keyId, 160, "ownerEvidence.keyId");
        }

        public static StoredOwnerEvidence from(
                EmploymentAbsenceContract.ResponseEvidence evidence) {
            Objects.requireNonNull(evidence, "evidence");
            return new StoredOwnerEvidence(
                    evidence.tenantId(), evidence.tenantGrantRevision(),
                    evidence.businessTimeZone(), evidence.purposeCode(),
                    evidence.projectionId(), evidence.authorizationRevision(),
                    evidence.fieldPolicyRevision(), evidence.issuedAt(),
                    evidence.asOf(), evidence.validUntil(), evidence.scopeDigest(),
                    evidence.contentSha256(), evidence.evidenceDigest(),
                    evidence.keyId());
        }
    }

    public record InvalidationEvidence(
            long eventSequence,
            UUID eventId,
            String changeType,
            LocalDate effectiveDate,
            String payloadSha256,
            String correlationId) {
        public InvalidationEvidence {
            if (eventSequence <= 0) {
                throw new IllegalArgumentException("eventSequence must be positive");
            }
            Objects.requireNonNull(eventId, "eventId");
            EmploymentAbsenceContract.code(changeType, "changeType");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            EmploymentAbsenceContract.digest(payloadSha256, "payloadSha256");
            EmploymentAbsenceContract.bounded(correlationId, 160, "correlationId");
        }
    }
}
