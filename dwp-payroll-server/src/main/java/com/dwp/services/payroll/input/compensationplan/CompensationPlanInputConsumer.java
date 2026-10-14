package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.core.event.DomainEventEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.QUARANTINED;

/**
 * Idempotent broker consumer for BASE-TFR-PAY-016. The event is only an
 * invalidation signal: every effective write follows an exact owner refetch.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.payroll-compensation-plan-input.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class CompensationPlanInputConsumer
        implements CompensationPlanInputEventHandler {

    private static final String SERVICE_ACTOR_TYPE = "SERVICE";

    private final CompensationPlanInputRepository repository;
    private final CompensationPlanSnapshotOwnerPort owner;
    private final CompensationPlanInputCanonicalizer canonicalizer;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String serviceActorId;

    @Autowired
    CompensationPlanInputConsumer(
            CompensationPlanInputRepository repository,
            CompensationPlanSnapshotOwnerPort owner,
            CompensationPlanInputCanonicalizer canonicalizer,
            PlatformTransactionManager transactionManager,
            @Value("${spring.application.name:dwp-payroll-server}")
            String serviceActorId) {
        this(repository, owner, canonicalizer, transactionManager,
                Clock.systemUTC(), serviceActorId);
    }

    CompensationPlanInputConsumer(
            CompensationPlanInputRepository repository,
            CompensationPlanSnapshotOwnerPort owner,
            CompensationPlanInputCanonicalizer canonicalizer,
            PlatformTransactionManager transactionManager,
            Clock clock,
            String serviceActorId) {
        this.repository = Objects.requireNonNull(repository);
        this.owner = Objects.requireNonNull(owner);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        if (serviceActorId == null || serviceActorId.isBlank()) {
            throw new IllegalArgumentException("serviceActorId is required");
        }
        this.serviceActorId = serviceActorId;
    }

    @Override
    public CompensationPlanInputContracts.ConsumptionResult consume(
            DomainEventEnvelope envelope) {
        CompensationPlanInputContracts.Publication publication =
                canonicalizer.publication(envelope);
        return consume(publication, false, SERVICE_ACTOR_TYPE, serviceActorId);
    }

    public CompensationPlanInputContracts.ConsumptionResult replay(
            long tenantId,
            UUID sourceEventId,
            CompensationPlanInputContracts.ReplayAuthority authority) {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(authority, "authority");
        DomainEventEnvelope envelope = transaction.execute(status -> {
            Instant now = clock.instant();
            repository.auditReplay(tenantId, sourceEventId, authority, now);
            return repository.quarantinedEvent(tenantId, sourceEventId);
        });
        if (envelope == null) {
            throw new IllegalStateException("PAY-016 replay transaction returned no event.");
        }
        CompensationPlanInputContracts.Publication publication =
                canonicalizer.publication(envelope);
        return consume(
                publication, true, authority.actorType(), authority.actorId());
    }

    private CompensationPlanInputContracts.ConsumptionResult consume(
            CompensationPlanInputContracts.Publication publication,
            boolean replay,
            String actorType,
            String actorId) {
        long tenantId = publication.envelope().tenantId();
        UUID eventId = publication.envelope().id();
        CompensationPlanInputRepository.Registration registration =
                transaction.execute(status -> repository.register(
                        publication, clock.instant()));
        if (registration == null) {
            throw new IllegalStateException("PAY-016 inbox registration returned no result.");
        }
        if (registration == CompensationPlanInputRepository.Registration.EVENT_ID_REUSED) {
            CompensationPlanInputContracts.ConsumptionResult result = transaction.execute(
                    status -> repository.quarantineEventIdReuse(
                            publication, actorType, actorId, clock.instant()));
            if (result == null) {
                throw new IllegalStateException(
                        "PAY-016 event ID reuse quarantine returned no result.");
            }
            return result;
        }

        Optional<CompensationPlanInputContracts.Receipt> receipt =
                transaction.execute(status -> repository.receipt(tenantId, eventId));
        if (receipt != null && receipt.isPresent()) {
            return receipt.orElseThrow().result(
                    CompensationPlanInputContracts.Outcome.REPLAYED);
        }
        Optional<CompensationPlanInputRepository.Attempt> latest =
                transaction.execute(status -> repository.latestAttempt(tenantId, eventId));
        if (!replay && latest != null && latest
                .filter(value -> "QUARANTINED".equals(value.outcome()))
                .isPresent()) {
            CompensationPlanInputRepository.Attempt attempt = latest.orElseThrow();
            return new CompensationPlanInputContracts.ConsumptionResult(
                    QUARANTINED, eventId, null,
                    publication.event().snapshotId(),
                    publication.event().snapshotRevision(), 0,
                    attempt.failureCode());
        }

        CompensationPlanSnapshotOwnerPort.ExactSnapshotRequest request =
                new CompensationPlanSnapshotOwnerPort.ExactSnapshotRequest(
                        tenantId,
                        publication.event().snapshotId(),
                        publication.event().snapshotRevision(),
                        publication.event().payloadDigest(),
                        publication.event().correlationId(),
                        CompensationPlanInputContracts.OWNER_REFETCH_PURPOSE,
                        CompensationPlanInputContracts.OWNER_REFETCH_PROJECTION);
        Xcon020ApprovedCompensationPlanSnapshotV1 snapshot;
        try {
            snapshot = Objects.requireNonNull(
                    owner.refetch(request), "owner refetch returned null");
        } catch (CompensationPlanSnapshotOwnerPort.SnapshotRejectedException exception) {
            return quarantine(
                    publication, replay, actorType, actorId,
                    "OWNER_SNAPSHOT_REJECTED", exception.getMessage());
        } catch (CompensationPlanSnapshotOwnerPort.TemporarilyUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CompensationPlanSnapshotOwnerPort.TemporarilyUnavailableException(
                    "Exact approved compensation snapshot refetch failed.", exception);
        }

        CompensationPlanInputContracts.MaterializedSnapshot materialized;
        try {
            materialized = canonicalizer.verify(publication, snapshot);
        } catch (CompensationPlanInputCanonicalizer
                .InvalidCompensationPlanInputException exception) {
            return quarantine(
                    publication, replay, actorType, actorId,
                    "SNAPSHOT_CONTRACT_MISMATCH", exception.getMessage());
        }

        String attemptKind = replay ? "REPLAY" : "CONSUME";
        CompensationPlanInputRepository.ApplyResult applied = transaction.execute(
                status -> repository.apply(
                        publication, materialized, attemptKind,
                        actorType, actorId, clock.instant()));
        if (applied == null) {
            throw new IllegalStateException("PAY-016 materialization returned no result.");
        }
        return applied.result();
    }

    private CompensationPlanInputContracts.ConsumptionResult quarantine(
            CompensationPlanInputContracts.Publication publication,
            boolean replay,
            String actorType,
            String actorId,
            String failureCode,
            String failureDetail) {
        CompensationPlanInputContracts.ConsumptionResult result = transaction.execute(
                status -> repository.quarantine(
                        publication, replay ? "REPLAY" : "CONSUME",
                        actorType, actorId, failureCode, failureDetail,
                        clock.instant()));
        if (result == null) {
            throw new IllegalStateException("PAY-016 quarantine returned no result.");
        }
        return result;
    }
}
