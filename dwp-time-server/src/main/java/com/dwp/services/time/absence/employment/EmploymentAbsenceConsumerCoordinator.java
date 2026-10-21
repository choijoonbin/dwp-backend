package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventInboxOffsetRecoverySupport;
import com.dwp.core.event.DomainEventInboxRepository;
import com.dwp.core.event.DomainEventInboxStrictValidationSupport;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.FreshnessState;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerDenied;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerFailure;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerFailureKind;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerRecovered;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerResult;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.RecoveryOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded TIM-003 coordinator. Remote owner I/O happens only between short transactions;
 * every final projection/inbox/offset/audit mutation shares one transaction and CAS fence.
 */
public final class EmploymentAbsenceConsumerCoordinator {

    private static final Duration INBOX_LEASE = Duration.ofSeconds(45);
    private static final int MAXIMUM_ATTEMPTS = 5;

    private final DomainEventInboxRepository inbox;
    private final EmploymentAbsenceProjectionStore projections;
    private final EmploymentAbsenceOwnerClient owner;
    private final EmploymentAbsenceAuditSink audit;
    private final EmploymentAbsenceDeferredInboxActivator deferredActivator;
    private final EmploymentAbsenceActivationGate gate;
    private final Xcon004EmploymentChangedV1Consumer contract;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String workerId;

    public EmploymentAbsenceConsumerCoordinator(
            DomainEventInboxRepository inbox,
            EmploymentAbsenceProjectionStore projections,
            EmploymentAbsenceOwnerClient owner,
            EmploymentAbsenceAuditSink audit,
            EmploymentAbsenceDeferredInboxActivator deferredActivator,
            EmploymentAbsenceActivationGate gate,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Clock clock,
            String workerId) {
        this.inbox = inbox;
        this.projections = projections;
        this.owner = owner;
        this.audit = audit;
        this.deferredActivator = deferredActivator;
        this.gate = gate;
        this.mapper = mapper;
        this.contract = new Xcon004EmploymentChangedV1Consumer(mapper);
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        EmploymentAbsenceContract.bounded(workerId, 160, "workerId");
        this.workerId = workerId;
    }

    public DeliveryOutcome consume(DomainEventEnvelope event) {
        if (event == null || event.tenantId() == null
                || !gate.permits(event.tenantId())) {
            return DeliveryOutcome.DISABLED;
        }
        Prepared prepared = transactions.execute(status -> prepare(event));
        if (prepared == null || prepared.terminal != null) {
            return prepared == null ? DeliveryOutcome.RETRYABLE : prepared.terminal;
        }
        UUID ownerCorrelationId = UUID.randomUUID();
        OwnerResult ownerResult = owner.recover(
                prepared.projection.tenantId(),
                prepared.projection.relationshipPublicId(),
                prepared.minimumRevision, ownerCorrelationId);
        return transactions.execute(status -> prepared.projectionOnlyReplayRefresh
                ? finalizeProjectionRefresh(
                        new RefreshClaim(prepared.projection,
                                prepared.minimumRevision, ownerCorrelationId),
                        ownerResult, "TIM003_STRICT_REPLAY_REFRESH")
                : finalizeSequential(prepared, ownerResult));
    }

    /** Eventless current-date baseline; the core primitive rechecks zero inbox and offset zero. */
    public DeliveryOutcome recoverBaseline(long tenantId, UUID relationshipPublicId) {
        if (!gate.permits(tenantId)) return DeliveryOutcome.DISABLED;
        BaselineClaim claim = transactions.execute(status -> {
            projections.lockTuple(tenantId, relationshipPublicId);
            if (projections.hasAnyInbox(tenantId, relationshipPublicId)
                    || projections.sharedOffset(tenantId, relationshipPublicId)
                            .orElse(0) != 0) {
                return null;
            }
            Optional<EmploymentAbsenceProjection> existing =
                    projections.find(tenantId, relationshipPublicId);
            if (existing.isEmpty()) {
                EmploymentAbsenceProjection inserted = projections.insertExpectedZero(
                        EmploymentAbsenceProjection.baselineClaim(
                                tenantId, relationshipPublicId, clock.instant()));
                return new BaselineClaim(inserted, UUID.randomUUID());
            }
            EmploymentAbsenceProjection current = existing.get();
            if (!eligibleBaselineRetry(current)) return null;
            try {
                EmploymentAbsenceProjection reclaimed =
                        projections.updateExpectedVersion(
                                current.claimRecovery(false, clock.instant()),
                                current.projectionVersion());
                return new BaselineClaim(reclaimed, UUID.randomUUID());
            } catch (IllegalStateException activeOrBackoff) {
                return null;
            }
        });
        if (claim == null) return DeliveryOutcome.BLOCKED;
        OwnerResult result = owner.recover(
                tenantId, relationshipPublicId, 0, claim.correlationId);
        return transactions.execute(status -> finalizeSnapshotRecovery(
                claim.projection, result,
                DomainEventInboxOffsetRecoverySupport.RecoveryMode.OWNER_BASELINE,
                claim.correlationId));
    }

    /** Bounded gap recovery; only DEFERRED rows can be settled by the core primitive. */
    public DeliveryOutcome recoverGap(long tenantId, UUID relationshipPublicId) {
        if (!gate.permits(tenantId)) return DeliveryOutcome.DISABLED;
        GapClaim claim = transactions.execute(status -> {
            projections.lockTuple(tenantId, relationshipPublicId);
            EmploymentAbsenceProjection current = projections.find(
                    tenantId, relationshipPublicId).orElse(null);
            if (current == null || current.freshness() != FreshnessState.GAP) return null;
            EmploymentAbsenceProjection claimed = projections.updateExpectedVersion(
                    current.claimRecovery(true, clock.instant()),
                    current.projectionVersion());
            long minimum = ownerRevisionFloor(claimed);
            return new GapClaim(claimed, minimum, UUID.randomUUID());
        });
        if (claim == null) return DeliveryOutcome.BLOCKED;
        OwnerResult result = owner.recover(tenantId, relationshipPublicId,
                claim.minimumRevision, claim.correlationId);
        return transactions.execute(status -> finalizeSnapshotRecovery(
                claim.projection, result,
                DomainEventInboxOffsetRecoverySupport.RecoveryMode.GAP,
                claim.correlationId));
    }

    /** Fenced current-date/TTL/authority refresh; never changes inbox or shared offset. */
    public DeliveryOutcome refreshCurrent(long tenantId, UUID relationshipPublicId) {
        if (!gate.permits(tenantId)) return DeliveryOutcome.DISABLED;
        RefreshClaim claim = transactions.execute(status -> {
            projections.lockTuple(tenantId, relationshipPublicId);
            if (projections.hasUnresolvedInbox(tenantId, relationshipPublicId)) {
                return null;
            }
            EmploymentAbsenceProjection current = projections.find(
                    tenantId, relationshipPublicId).orElse(null);
            if (current == null || current.freshness() == FreshnessState.GAP
                    || current.freshness() == FreshnessState.QUARANTINED
                    || current.isOwnerBaselinePlaceholder()) {
                return null;
            }
            try {
                EmploymentAbsenceProjection claimed =
                        projections.updateExpectedVersion(
                                current.claimRecovery(false, clock.instant()),
                                current.projectionVersion());
                long minimum = ownerRevisionFloor(claimed);
                return new RefreshClaim(claimed, minimum, UUID.randomUUID());
            } catch (IllegalStateException activeOrBackoff) {
                return null;
            }
        });
        if (claim == null) return DeliveryOutcome.BLOCKED;
        OwnerResult result = owner.recover(
                tenantId, relationshipPublicId,
                claim.minimumRevision, claim.correlationId);
        return transactions.execute(status -> finalizeCurrentRefresh(claim, result));
    }

    /** Converges one corrupted stored envelope without aborting the reconciler batch. */
    public DeliveryOutcome reconcileMalformedStoredEnvelope(
            UUID eventId,
            String payloadSha256,
            long tenantId,
            String aggregateId) {
        if (!gate.permits(tenantId)) return DeliveryOutcome.DISABLED;
        return transactions.execute(status -> {
            inbox.finalizeRegisteredInvalid(
                    EmploymentAbsenceContract.CONSUMER, eventId,
                    payloadSha256, "stored TIM-003 envelope is invalid");
            UUID relationshipPublicId = canonicalRelationshipId(aggregateId);
            if (relationshipPublicId != null) {
                projections.lockTuple(tenantId, relationshipPublicId);
                block(tenantId, relationshipPublicId, "EVENT_CONTRACT_VIOLATION");
            }
            return DeliveryOutcome.DEAD;
        });
    }

    private static UUID canonicalRelationshipId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Prepared prepare(DomainEventEnvelope event) {
        DomainEventInboxStrictValidationSupport.RegistrationResult registration =
                inbox.registerForStrictValidation(EmploymentAbsenceContract.CONSUMER, event);
        lockSafeEventCandidates(event.tenantId(), event);
        if (registration.state()
                == DomainEventInboxStrictValidationSupport.RegistrationState.PAYLOAD_CONFLICT) {
            blockConflictCandidates(registration, event, "PAYLOAD_CONFLICT");
            return Prepared.terminal(DeliveryOutcome.DEAD);
        }

        Xcon004EmploymentChangedV1Consumer.ValidatedEvent valid;
        try {
            valid = contract.validate(event, registration.incoming().payloadSha256());
        } catch (RuntimeException invalid) {
            inbox.finalizeRegisteredInvalid(
                    EmploymentAbsenceContract.CONSUMER, event.id(),
                    registration.incoming().payloadSha256(), invalid.getMessage());
            blockInvalidCandidates(event, event.tenantId(), "EVENT_CONTRACT_VIOLATION");
            return Prepared.terminal(DeliveryOutcome.DEAD);
        }

        DomainEventInboxRepository.BeginResult begun = inbox.begin(
                EmploymentAbsenceContract.CONSUMER, event, workerId, INBOX_LEASE);
        switch (begun.state()) {
            case DUPLICATE -> {
                Prepared replayRefresh = prepareDuplicateReplayRefresh(
                        registration, valid);
                return replayRefresh == null
                        ? Prepared.terminal(DeliveryOutcome.DUPLICATE)
                        : replayRefresh;
            }
            case DEAD -> {
                blockInvalidCandidates(event, event.tenantId(),
                        canonicalDeadReason(registration.stored().lastError()));
                return Prepared.terminal(DeliveryOutcome.DEAD);
            }
            case PAYLOAD_CONFLICT -> {
                blockInvalidCandidates(event, event.tenantId(), "PAYLOAD_CONFLICT");
                return Prepared.terminal(DeliveryOutcome.DEAD);
            }
            case BUSY, DEFERRED -> { return Prepared.terminal(DeliveryOutcome.BLOCKED); }
            case OUT_OF_ORDER -> {
                markGap(valid);
                return Prepared.terminal(DeliveryOutcome.GAP);
            }
            case ACQUIRED -> { }
        }

        Optional<EmploymentAbsenceProjection> existing = projections.find(
                event.tenantId(), valid.body().relationshipPublicId());
        EmploymentAbsenceProjection base = existing.orElseGet(() ->
                EmploymentAbsenceProjection.baselineClaim(
                        event.tenantId(), valid.body().relationshipPublicId(), clock.instant()));

        boolean blockerPresent = projections.hasBlockingInbox(
                event.tenantId(), valid.body().relationshipPublicId());
        boolean lawfulStrictReplay = registration.stored() != null
                && "REPLAY_PENDING".equals(registration.stored().status())
                && base.freshness() == FreshnessState.QUARANTINED
                && !"PAYLOAD_CONFLICT".equals(base.lastRecoveryErrorCode())
                && !blockerPresent;
        if ((base.freshness() == FreshnessState.QUARANTINED
                && !lawfulStrictReplay) || blockerPresent) {
            EmploymentAbsenceProjection quarantined =
                    base.freshness() == FreshnessState.QUARANTINED ? base
                            : base.failed(OwnerFailure.permanent("INBOX_DEAD_BLOCKER"),
                                    false, clock.instant());
            EmploymentAbsenceProjection next = quarantined.observeWhileQuarantined(
                    valid.invalidation(), event.aggregateSequence());
            persist(existing, next);
            inbox.completeTim003ActiveLease(
                    EmploymentAbsenceContract.CONSUMER, event, begun.lockToken());
            appendEventAudit(valid, "TIM003_QUARANTINE_PRECEDENCE", "DENIED");
            return Prepared.terminal(DeliveryOutcome.APPLIED);
        }

        if (existing.isPresent() && base.freshness() == FreshnessState.GAP
                && event.aggregateSequence() == base.sharedOffsetSequence() + 1
                && event.aggregateSequence() < base.lastObservedEventSequence()) {
            EmploymentAbsenceProjection next = base.gapBackfill(
                    event.aggregateSequence(), clock.instant());
            persist(existing, next);
            if (next.freshness() == FreshnessState.REFETCH_PENDING) {
                deferredActivator.activateExact(
                        event.tenantId(), valid.body().relationshipPublicId(),
                        next.lastObservedEventSequence());
            }
            inbox.completeTim003ActiveLease(
                    EmploymentAbsenceContract.CONSUMER, event, begun.lockToken());
            appendEventAudit(valid, "TIM003_GAP_BACKFILL", "SUCCESS");
            return Prepared.terminal(DeliveryOutcome.APPLIED);
        }

        if (existing.isPresent() && base.freshness() == FreshnessState.CURRENT
                && base.ownerSnapshot() != null
                && base.ownerSnapshot().relationshipRevision() >= event.aggregateSequence()
                && event.aggregateSequence() == base.sharedOffsetSequence() + 1
                && owner.currentlyAuthorizes(base)) {
            EmploymentAbsenceProjection next = base.covered(
                    valid.invalidation(), event.aggregateSequence(), clock.instant());
            persist(existing, next);
            inbox.completeTim003ActiveLease(
                    EmploymentAbsenceContract.CONSUMER, event, begun.lockToken());
            appendEventAudit(valid, "TIM003_CONTIGUOUS_COVERED", "SUCCESS");
            return Prepared.terminal(DeliveryOutcome.APPLIED_NO_REFETCH);
        }

        EmploymentAbsenceProjection pending = base.observe(
                valid.invalidation(), base.sharedOffsetSequence(),
                FreshnessState.REFETCH_PENDING, RecoveryOutcome.PENDING,
                clock.instant());
        EmploymentAbsenceProjection stored = persist(existing, pending);
        long minimum = ownerRevisionFloor(stored);
        return new Prepared(valid, stored, begun.lockToken(), begun.attempt(),
                minimum, false, null);
    }

    private Prepared prepareDuplicateReplayRefresh(
            DomainEventInboxStrictValidationSupport.RegistrationResult registration,
            Xcon004EmploymentChangedV1Consumer.ValidatedEvent valid) {
        if (registration.stored() == null
                || !"REPLAY_PENDING".equals(registration.stored().status())) {
            return null;
        }
        long tenantId = valid.envelope().tenantId();
        UUID relationshipId = valid.body().relationshipPublicId();
        Optional<EmploymentAbsenceProjection> existing =
                projections.find(tenantId, relationshipId);
        if (existing.isEmpty()) return null;
        EmploymentAbsenceProjection current = existing.get();
        if (current.freshness() != FreshnessState.QUARANTINED
                || "PAYLOAD_CONFLICT".equals(current.lastRecoveryErrorCode())
                || current.requiredRelationshipRevision()
                    > current.sharedOffsetSequence()
                || projections.hasBlockingInbox(tenantId, relationshipId)) {
            return null;
        }
        EmploymentAbsenceProjection released = projections.updateExpectedVersion(
                current.releaseQuarantine(clock.instant()),
                current.projectionVersion());
        audit.append(tenantId, relationshipId,
                UUID.fromString(valid.envelope().correlationId()),
                "TIM003_STRICT_REPLAY_RELEASE", "SUCCESS",
                Map.of("eventId", valid.envelope().id().toString(),
                        "projectionExpectedVersion", current.projectionVersion(),
                        "projectionResultVersion", released.projectionVersion()));
        long minimum = ownerRevisionFloor(released);
        return new Prepared(valid, released, null, 0, minimum, true, null);
    }

    private DeliveryOutcome finalizeSequential(Prepared claim, OwnerResult result) {
        projections.lockTuple(
                claim.projection.tenantId(), claim.projection.relationshipPublicId());
        EmploymentAbsenceProjection current = exactClaim(claim.projection);
        result = owner.revalidateForFinalization(
                result, current.relationshipPublicId(), claim.minimumRevision);
        if (result instanceof OwnerRecovered recovered) {
            EmploymentAbsenceProjection next = current.recovered(
                    recovered.snapshot(), recovered.evidence(),
                    recovered.grant().businessTimeZone(), claim.minimumRevision,
                    claim.event.envelope().aggregateSequence(), clock.instant());
            projections.updateExpectedVersion(next, current.projectionVersion());
            inbox.completeTim003ActiveLease(EmploymentAbsenceContract.CONSUMER,
                    claim.event.envelope(), claim.lockToken);
            appendEventAudit(claim.event, "TIM003_SEQUENTIAL_RECOVERY", "SUCCESS");
            return DeliveryOutcome.APPLIED;
        }
        OwnerFailure failure = ((OwnerDenied) result).failure();
        int effectiveMaximum = switch (failure.kind()) {
            case PERMANENT -> claim.attempt;
            case CONTEXT_REFRESH -> claim.attempt + 1;
            case RETRYABLE -> MAXIMUM_ATTEMPTS;
        };
        DomainEventInboxRepository.FailureState ledgerFailure =
                inbox.failTim003ActiveLease(
                        EmploymentAbsenceContract.CONSUMER,
                        claim.event.envelope().id(), claim.lockToken,
                        claim.attempt, effectiveMaximum, failure.code());
        OwnerFailure projectionFailure = ledgerFailure
                == DomainEventInboxRepository.FailureState.DEAD
                ? OwnerFailure.permanent(failure.code()) : failure;
        EmploymentAbsenceProjection next = current.failed(
                projectionFailure, false, clock.instant());
        projections.updateExpectedVersion(next, current.projectionVersion());
        appendEventAudit(claim.event, "TIM003_SEQUENTIAL_RECOVERY", "DENIED");
        return ledgerFailure == DomainEventInboxRepository.FailureState.DEAD
                ? DeliveryOutcome.DEAD : DeliveryOutcome.RETRYABLE;
    }

    private DeliveryOutcome finalizeSnapshotRecovery(
            EmploymentAbsenceProjection claim,
            OwnerResult result,
            DomainEventInboxOffsetRecoverySupport.RecoveryMode mode,
            UUID correlationId) {
        projections.lockTuple(claim.tenantId(), claim.relationshipPublicId());
        EmploymentAbsenceProjection current = exactClaim(claim);
        long minimum = mode == DomainEventInboxOffsetRecoverySupport.RecoveryMode.OWNER_BASELINE
                ? 0 : current.requiredRelationshipRevision();
        result = owner.revalidateForFinalization(
                result, current.relationshipPublicId(), minimum);
        if (result instanceof OwnerDenied denied) {
            EmploymentAbsenceProjection failed = current.failed(
                    denied.failure(), mode
                            == DomainEventInboxOffsetRecoverySupport.RecoveryMode.GAP,
                    clock.instant());
            projections.updateExpectedVersion(failed, current.projectionVersion());
            audit.append(current.tenantId(), current.relationshipPublicId(), correlationId,
                    "TIM003_OWNER_SNAPSHOT_RECOVERY", "DENIED",
                    Map.of("code", denied.failure().code(), "mode", mode.name()));
            return denied.failure().kind() == OwnerFailureKind.PERMANENT
                    ? DeliveryOutcome.DEAD : DeliveryOutcome.RETRYABLE;
        }
        OwnerRecovered recovered = (OwnerRecovered) result;
        long recoveredOffset = recovered.snapshot().relationshipRevision();
        EmploymentAbsenceProjection next = current.recovered(
                recovered.snapshot(), recovered.evidence(),
                recovered.grant().businessTimeZone(),
                mode == DomainEventInboxOffsetRecoverySupport.RecoveryMode.OWNER_BASELINE
                        ? 0 : current.requiredRelationshipRevision(),
                recoveredOffset, clock.instant());
        EmploymentAbsenceProjection stored = projections.updateExpectedVersion(
                next, current.projectionVersion());
        inbox.recoverOffsetFromOwnerSnapshot(
                new DomainEventInboxOffsetRecoverySupport.OffsetRecoveryRequest(
                        EmploymentAbsenceContract.CONSUMER, current.tenantId(),
                        EmploymentAbsenceContract.EVENT_SOURCE,
                        EmploymentAbsenceContract.EVENT_TYPE,
                        EmploymentAbsenceContract.AGGREGATE_TYPE,
                        current.relationshipPublicId().toString(),
                        current.sharedOffsetSequence(), recoveredOffset, mode,
                        DomainEventInboxOffsetRecoverySupport.RECOVERY_ACTOR,
                        recovered.snapshot().contentSha256(),
                        recovered.snapshot().scopeDigest(), correlationId.toString(),
                        Map.of(
                                "tenantGrantRevision",
                                recovered.grant().tenantGrantRevision(),
                                "authorizationRevision",
                                recovered.grant().authorizationRevision(),
                                "fieldPolicyRevision",
                                recovered.grant().fieldPolicyRevision(),
                                "businessTimeZone",
                                recovered.grant().businessTimeZone(),
                                "projectionExpectedVersion",
                                current.projectionVersion(),
                                "projectionResultVersion",
                                stored.projectionVersion(),
                                "ownerEvidenceDigest",
                                recovered.evidence().evidenceDigest())));
        return DeliveryOutcome.APPLIED;
    }

    private DeliveryOutcome finalizeCurrentRefresh(
            RefreshClaim claim,
            OwnerResult result) {
        return finalizeProjectionRefresh(
                claim, result, "TIM003_CURRENT_CONTEXT_REFRESH");
    }

    private DeliveryOutcome finalizeProjectionRefresh(
            RefreshClaim claim,
            OwnerResult result,
            String auditAction) {
        projections.lockTuple(
                claim.projection.tenantId(), claim.projection.relationshipPublicId());
        EmploymentAbsenceProjection current = exactClaim(claim.projection);
        if (projections.hasUnresolvedInbox(
                current.tenantId(), current.relationshipPublicId())) {
            return DeliveryOutcome.BLOCKED;
        }
        result = owner.revalidateForFinalization(
                result, current.relationshipPublicId(), claim.minimumRevision);
        if (result instanceof OwnerDenied denied) {
            EmploymentAbsenceProjection failed = current.failed(
                    denied.failure(), false, clock.instant());
            EmploymentAbsenceProjection stored = projections.updateExpectedVersion(
                    failed, current.projectionVersion());
            audit.append(current.tenantId(), current.relationshipPublicId(),
                    claim.correlationId, auditAction, "DENIED",
                    Map.of("code", denied.failure().code(),
                            "projectionVersion", stored.projectionVersion()));
            return denied.failure().kind() == OwnerFailureKind.PERMANENT
                    ? DeliveryOutcome.DEAD : DeliveryOutcome.RETRYABLE;
        }
        OwnerRecovered recovered = (OwnerRecovered) result;
        EmploymentAbsenceProjection next = current.recovered(
                recovered.snapshot(), recovered.evidence(),
                recovered.grant().businessTimeZone(), claim.minimumRevision,
                current.sharedOffsetSequence(), clock.instant());
        EmploymentAbsenceProjection stored = projections.updateExpectedVersion(
                next, current.projectionVersion());
        audit.append(current.tenantId(), current.relationshipPublicId(),
                claim.correlationId, auditAction, "SUCCESS",
                Map.of("projectionExpectedVersion", current.projectionVersion(),
                        "projectionResultVersion", stored.projectionVersion(),
                        "sharedOffsetSequence", current.sharedOffsetSequence()));
        return DeliveryOutcome.APPLIED;
    }

    private EmploymentAbsenceProjection exactClaim(EmploymentAbsenceProjection claim) {
        EmploymentAbsenceProjection current = projections.find(
                        claim.tenantId(), claim.relationshipPublicId())
                .orElseThrow(() -> new IllegalStateException("projection claim disappeared"));
        if (current.projectionVersion() != claim.projectionVersion()) {
            throw new JdbcEmploymentAbsenceProjectionStore.ProjectionCasLost(
                    "projection claim version changed");
        }
        return current;
    }

    private void markGap(Xcon004EmploymentChangedV1Consumer.ValidatedEvent valid) {
        long tenantId = valid.envelope().tenantId();
        UUID relationshipId = valid.body().relationshipPublicId();
        Optional<EmploymentAbsenceProjection> existing = projections.find(
                tenantId, relationshipId);
        EmploymentAbsenceProjection base = existing.orElseGet(() ->
                EmploymentAbsenceProjection.baselineClaim(
                        tenantId, relationshipId, clock.instant()));
        boolean blockerPresent = projections.hasBlockingInbox(tenantId, relationshipId);
        if (base.freshness() == FreshnessState.QUARANTINED || blockerPresent) {
            EmploymentAbsenceProjection blocked =
                    base.freshness() == FreshnessState.QUARANTINED ? base
                            : base.failed(OwnerFailure.permanent("INBOX_DEAD_BLOCKER"),
                                    false, clock.instant());
            EmploymentAbsenceProjection quarantined =
                    blocked.observeWhileQuarantined(
                            valid.invalidation(), base.sharedOffsetSequence());
            persist(existing, quarantined);
            appendEventAudit(valid, "TIM003_QUARANTINE_PRECEDENCE", "DENIED");
            return;
        }
        EmploymentAbsenceProjection gap = base.observe(
                valid.invalidation(), base.sharedOffsetSequence(),
                FreshnessState.GAP, RecoveryOutcome.NOT_ATTEMPTED, clock.instant());
        persist(existing, gap);
        appendEventAudit(valid, "TIM003_GAP_DETECTED", "SUCCESS");
    }

    private void blockConflictCandidates(
            DomainEventInboxStrictValidationSupport.RegistrationResult registration,
            DomainEventEnvelope incoming,
            String reason) {
        Long storedTenant = registration.stored().tenantId();
        if (storedTenant == null || storedTenant <= 0) return;
        Set<UUID> candidates = new LinkedHashSet<>();
        DomainEventEnvelope stored = parseStored(registration.stored().payload());
        addCandidates(candidates, stored);
        if (incoming.tenantId() != null && incoming.tenantId().equals(storedTenant)) {
            addCandidates(candidates, incoming);
        }
        candidates.stream().sorted().forEach(candidate -> {
            projections.lockTuple(storedTenant, candidate);
            block(storedTenant, candidate, reason);
        });
    }

    private void blockInvalidCandidates(
            DomainEventEnvelope event,
            long tenantId,
            String reason) {
        Set<UUID> candidates = new LinkedHashSet<>();
        addCandidates(candidates, event);
        candidates.stream().sorted().forEach(candidate -> {
            projections.lockTuple(tenantId, candidate);
            block(tenantId, candidate, reason);
        });
    }

    private void addCandidates(Set<UUID> candidates, DomainEventEnvelope event) {
        if (event == null) return;
        UUID envelope = contract.safeEnvelopeRelationshipCandidate(event);
        UUID body = contract.safeBodyRelationshipCandidate(event);
        if (envelope != null) candidates.add(envelope);
        if (body != null) candidates.add(body);
    }

    private void lockSafeEventCandidates(long tenantId, DomainEventEnvelope event) {
        Set<UUID> candidates = new java.util.TreeSet<>();
        addCandidates(candidates, event);
        candidates.forEach(candidate -> projections.lockTuple(tenantId, candidate));
    }

    private DomainEventEnvelope parseStored(String payload) {
        try {
            return mapper.readValue(payload, DomainEventEnvelope.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private void block(long tenantId, UUID relationshipPublicId, String reason) {
        Optional<EmploymentAbsenceProjection> existing = projections.find(
                tenantId, relationshipPublicId);
        if (existing.isPresent()
                && existing.get().freshness() == FreshnessState.QUARANTINED) {
            return;
        }
        EmploymentAbsenceProjection blocked = existing
                .map(value -> value.failed(OwnerFailure.permanent(reason),
                        false, clock.instant()))
                .orElseGet(() -> EmploymentAbsenceProjection.blocker(
                        tenantId, relationshipPublicId, reason, clock.instant()));
        EmploymentAbsenceProjection stored = persist(existing, blocked);
        audit.append(tenantId, relationshipPublicId, UUID.randomUUID(),
                "TIM003_RELATIONSHIP_BLOCKED", "DENIED",
                Map.of("reason", reason, "projectionVersion", stored.projectionVersion()));
    }

    private static String canonicalDeadReason(String storedReason) {
        if (DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON
                .equals(storedReason)) {
            return "PAYLOAD_CONFLICT";
        }
        if (storedReason != null && storedReason.startsWith(
                DomainEventInboxStrictValidationSupport.INVALID_EVENT_REASON)) {
            return "EVENT_CONTRACT_VIOLATION";
        }
        try {
            EmploymentAbsenceContract.code(storedReason, "storedReason");
            return storedReason;
        } catch (RuntimeException ignored) {
            return "INBOX_DEAD_BLOCKER";
        }
    }

    private EmploymentAbsenceProjection persist(
            Optional<EmploymentAbsenceProjection> existing,
            EmploymentAbsenceProjection candidate) {
        return existing.isPresent()
                ? projections.updateExpectedVersion(
                        candidate, existing.get().projectionVersion())
                : projections.insertExpectedZero(candidate);
    }

    private static boolean eligibleBaselineRetry(EmploymentAbsenceProjection projection) {
        return projection.isOwnerBaselinePlaceholder();
    }

    private static long ownerRevisionFloor(EmploymentAbsenceProjection projection) {
        long ownerRevision = projection.ownerSnapshot() == null
                ? 0 : projection.ownerSnapshot().relationshipRevision();
        return Math.max(projection.sharedOffsetSequence(),
                Math.max(projection.requiredRelationshipRevision(), ownerRevision));
    }

    private void appendEventAudit(
            Xcon004EmploymentChangedV1Consumer.ValidatedEvent event,
            String action,
            String outcome) {
        audit.append(event.envelope().tenantId(), event.body().relationshipPublicId(),
                UUID.fromString(event.envelope().correlationId()), action, outcome,
                Map.of("eventId", event.envelope().id().toString(),
                        "eventSequence", event.envelope().aggregateSequence(),
                        "payloadSha256", event.payloadSha256()));
    }

    public enum DeliveryOutcome {
        DISABLED,
        APPLIED,
        APPLIED_NO_REFETCH,
        DUPLICATE,
        GAP,
        BLOCKED,
        RETRYABLE,
        DEAD
    }

    private record Prepared(
            Xcon004EmploymentChangedV1Consumer.ValidatedEvent event,
            EmploymentAbsenceProjection projection,
            String lockToken,
            int attempt,
            long minimumRevision,
            boolean projectionOnlyReplayRefresh,
            DeliveryOutcome terminal) {
        static Prepared terminal(DeliveryOutcome value) {
            return new Prepared(null, null, null, 0, 0, false, value);
        }
    }

    private record BaselineClaim(
            EmploymentAbsenceProjection projection,
            UUID correlationId) {
    }

    private record GapClaim(
            EmploymentAbsenceProjection projection,
            long minimumRevision,
            UUID correlationId) {
    }

    private record RefreshClaim(
            EmploymentAbsenceProjection projection,
            long minimumRevision,
            UUID correlationId) {
    }
}
