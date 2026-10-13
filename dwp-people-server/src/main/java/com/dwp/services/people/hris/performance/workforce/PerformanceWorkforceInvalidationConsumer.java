package com.dwp.services.people.hris.performance.workforce;

import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon005AssignmentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon006OrganizationChangedV1;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

/** Canonical XCON invalidation boundary for the PER workforce projection. */
@Component
public final class PerformanceWorkforceInvalidationConsumer {

    public static final String WORKER_CHANGED = "WorkerChanged.v1";
    public static final String EMPLOYMENT_CHANGED = "EmploymentChanged.v1";
    public static final String ASSIGNMENT_CHANGED = "AssignmentChanged.v1";
    public static final String ORGANIZATION_CHANGED = "OrganizationChanged.v1";

    private final PerformanceWorkforceProjectionStore store;

    public PerformanceWorkforceInvalidationConsumer(
            PerformanceWorkforceProjectionStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public InvalidationReceipt consumeWorkerChanged(
            TrustedEnvelope<Xcon003WorkerChangedV1> envelope) {
        Xcon003WorkerChangedV1 payload = requirePayload(
                envelope, Xcon003WorkerChangedV1.class, payloadDigest(envelope.payload()));
        return consume(new PerformanceWorkforceProjectionStore.InvalidationEvent(
                envelope.tenantId(),
                envelope.eventId(),
                WORKER_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.WORKER,
                payload.workerPublicId(),
                payload.newAggregateVersion(),
                payload.effectiveDate(),
                envelope.payloadDigest()));
    }

    public InvalidationReceipt consumeEmploymentChanged(
            TrustedEnvelope<Xcon004EmploymentChangedV1> envelope) {
        Xcon004EmploymentChangedV1 payload = requirePayload(
                envelope, Xcon004EmploymentChangedV1.class, payloadDigest(envelope.payload()));
        return consume(new PerformanceWorkforceProjectionStore.InvalidationEvent(
                envelope.tenantId(),
                envelope.eventId(),
                EMPLOYMENT_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.EMPLOYMENT,
                payload.relationshipPublicId(),
                payload.newAggregateVersion(),
                payload.effectiveDate(),
                envelope.payloadDigest()));
    }

    public InvalidationReceipt consumeAssignmentChanged(
            TrustedEnvelope<Xcon005AssignmentChangedV1> envelope) {
        Xcon005AssignmentChangedV1 payload = requirePayload(
                envelope, Xcon005AssignmentChangedV1.class, payloadDigest(envelope.payload()));
        if (!envelope.eventId().equals(payload.eventPublicId())) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.EVENT_ID_PAYLOAD_MISMATCH,
                    "AssignmentChanged.v1 eventPublicId does not match the trusted envelope.");
        }
        return consume(new PerformanceWorkforceProjectionStore.InvalidationEvent(
                envelope.tenantId(),
                envelope.eventId(),
                ASSIGNMENT_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.ASSIGNMENT,
                payload.assignmentPublicId(),
                payload.newAggregateVersion(),
                payload.effectiveDate(),
                envelope.payloadDigest()));
    }

    public InvalidationReceipt consumeOrganizationChanged(
            TrustedEnvelope<Xcon006OrganizationChangedV1> envelope) {
        Xcon006OrganizationChangedV1 payload = requirePayload(
                envelope, Xcon006OrganizationChangedV1.class, payloadDigest(envelope.payload()));
        return consume(new PerformanceWorkforceProjectionStore.InvalidationEvent(
                envelope.tenantId(),
                envelope.eventId(),
                ORGANIZATION_CHANGED,
                PerformanceWorkforceProjectionStore.SubjectType.ORGANIZATION,
                payload.revisionPublicId(),
                null,
                payload.effectiveDate(),
                envelope.payloadDigest()));
    }

    public static String payloadDigest(Xcon003WorkerChangedV1 payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return PerformanceWorkforceProjection.canonicalPayloadDigest(WORKER_CHANGED, md -> {
            PerformanceWorkforceProjection.put(md, payload.changeType());
            PerformanceWorkforceProjection.put(md, payload.effectiveDate().toString());
            PerformanceWorkforceProjection.put(md, payload.newAggregateVersion());
            PerformanceWorkforceProjection.put(md, payload.workerPublicId().toString());
        });
    }

    public static String payloadDigest(Xcon004EmploymentChangedV1 payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return PerformanceWorkforceProjection.canonicalPayloadDigest(EMPLOYMENT_CHANGED, md -> {
            PerformanceWorkforceProjection.put(md, payload.changeType());
            PerformanceWorkforceProjection.put(md, payload.effectiveDate().toString());
            PerformanceWorkforceProjection.put(md, payload.newAggregateVersion());
            PerformanceWorkforceProjection.put(md, payload.relationshipPublicId().toString());
        });
    }

    public static String payloadDigest(Xcon005AssignmentChangedV1 payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return PerformanceWorkforceProjection.canonicalPayloadDigest(ASSIGNMENT_CHANGED, md -> {
            PerformanceWorkforceProjection.put(md, payload.assignmentPublicId().toString());
            PerformanceWorkforceProjection.put(md, payload.changedReferences().size());
            payload.changedReferences().forEach(
                    value -> PerformanceWorkforceProjection.put(md, value));
            PerformanceWorkforceProjection.put(md, payload.effectiveDate().toString());
            PerformanceWorkforceProjection.put(md, payload.eventPublicId().toString());
            PerformanceWorkforceProjection.put(md, payload.eventType());
            PerformanceWorkforceProjection.put(md, payload.newAggregateVersion());
        });
    }

    public static String payloadDigest(Xcon006OrganizationChangedV1 payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return PerformanceWorkforceProjection.canonicalPayloadDigest(ORGANIZATION_CHANGED, md -> {
            PerformanceWorkforceProjection.put(
                    md, payload.changedOrganizationPublicIds().size());
            payload.changedOrganizationPublicIds().forEach(
                    value -> PerformanceWorkforceProjection.put(md, value.toString()));
            PerformanceWorkforceProjection.put(md, payload.effectiveDate().toString());
            PerformanceWorkforceProjection.put(md, payload.revisionPublicId().toString());
            PerformanceWorkforceProjection.put(md, payload.snapshotDigest());
        });
    }

    private InvalidationReceipt consume(
            PerformanceWorkforceProjectionStore.InvalidationEvent event) {
        PerformanceWorkforceProjectionStore.InvalidationResult result = store.invalidate(event);
        return new InvalidationReceipt(
                result.eventId(),
                Outcome.valueOf(result.outcome().name()),
                result.duplicate(),
                result.projectionState(),
                result.requiredAggregateRevision(),
                result.observedAggregateRevision());
    }

    private static <T> T requirePayload(
            TrustedEnvelope<T> envelope,
            Class<T> expectedType,
            String calculatedDigest) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        T payload = Objects.requireNonNull(envelope.payload(), "payload must not be null");
        if (!expectedType.isInstance(payload)) {
            throw payloadMismatch("The trusted envelope contains the wrong XCON payload type.");
        }
        if (!calculatedDigest.equals(envelope.payloadDigest())) {
            throw payloadMismatch(
                    "The trusted envelope digest does not bind the canonical XCON payload.");
        }
        return payload;
    }

    private static PerformanceWorkforceProjectionException payloadMismatch(String message) {
        return new PerformanceWorkforceProjectionException(
                PerformanceWorkforceProjectionException.Code.EVENT_PAYLOAD_DIGEST_MISMATCH,
                message);
    }

    public enum Outcome {
        STALE,
        BLOCKED_DEPENDENCY_GAP,
        IGNORED_OLDER_REVISION,
        REPLAYED,
        QUARANTINED
    }

    public record TrustedEnvelope<T>(
            long tenantId,
            UUID eventId,
            String payloadDigest,
            T payload) {

        public TrustedEnvelope {
            if (tenantId <= 0) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            Objects.requireNonNull(eventId, "eventId must not be null");
            if (payloadDigest == null
                    || !PerformanceWorkforceProjection.SHA_256.matcher(payloadDigest).matches()) {
                throw new IllegalArgumentException(
                        "payloadDigest must be a lowercase SHA-256 digest");
            }
            Objects.requireNonNull(payload, "payload must not be null");
        }
    }

    public record InvalidationReceipt(
            UUID eventId,
            Outcome outcome,
            boolean duplicate,
            PerformanceWorkforceProjection.State projectionState,
            Long requiredAggregateRevision,
            Long observedAggregateRevision) {

        public InvalidationReceipt {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(projectionState, "projectionState must not be null");
        }
    }
}
