package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact generated XCON-004 and transport equality validator for TIM-003. */
public final class Xcon004EmploymentChangedV1Consumer {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "xcon", "XCON-004",
            "profile", "EmploymentChanged.v1");

    private final ObjectMapper mapper;
    private final DomainEventContractRegistry registry;

    public Xcon004EmploymentChangedV1Consumer(ObjectMapper mapper) {
        this.mapper = mapper;
        this.registry = new DomainEventContractRegistry();
        registry.register(EmploymentAbsenceContract.EVENT_TYPE, 1, 1);
    }

    public ValidatedEvent validate(
            DomainEventEnvelope event,
            String registeredPayloadSha256) {
        Objects.requireNonNull(event, "event");
        EmploymentAbsenceContract.digest(registeredPayloadSha256,
                "registeredPayloadSha256");
        registry.requireCompatible(event);
        if (!EmploymentAbsenceContract.EVENT_TYPE.equals(event.type())
                || !EmploymentAbsenceContract.EVENT_SOURCE.equals(event.source())
                || !EmploymentAbsenceContract.AGGREGATE_TYPE.equals(event.aggregateType())
                || event.schemaVersion() != 1
                || event.tenantId() == null || event.tenantId() <= 0
                || !EXTENSIONS.equals(event.extensions())) {
            throw new InvalidEmploymentEvent("transport contract differs");
        }
        Xcon004EmploymentChangedV1 body;
        try {
            body = mapper.treeToValue(event.data(), Xcon004EmploymentChangedV1.class);
        } catch (Exception exception) {
            throw new InvalidEmploymentEvent("XCON-004 is invalid", exception);
        }
        String relationship = body.relationshipPublicId().toString();
        if (!relationship.equals(event.aggregateId())
                || body.newAggregateVersion() != event.aggregateSequence()
                || !(EmploymentAbsenceContract.AGGREGATE_TYPE + "/" + relationship)
                        .equals(event.subject())) {
            throw new InvalidEmploymentEvent("envelope/body equality differs");
        }
        try {
            UUID correlationId = UUID.fromString(event.correlationId());
            if (!correlationId.toString().equals(event.correlationId())) {
                throw new IllegalArgumentException("noncanonical UUID");
            }
        } catch (RuntimeException invalidCorrelation) {
            throw new InvalidEmploymentEvent(
                    "correlationId must be a canonical UUID", invalidCorrelation);
        }
        return new ValidatedEvent(event, body, registeredPayloadSha256,
                new EmploymentAbsenceProjection.InvalidationEvidence(
                        body.newAggregateVersion(), event.id(), body.changeType(),
                        body.effectiveDate(), registeredPayloadSha256,
                        event.correlationId()));
    }

    /** Safely extracts a same-tenant blocker candidate without accepting the payload. */
    public UUID safeBodyRelationshipCandidate(DomainEventEnvelope event) {
        try {
            return mapper.treeToValue(event.data(), Xcon004EmploymentChangedV1.class)
                    .relationshipPublicId();
        } catch (Exception exception) {
            return null;
        }
    }

    public UUID safeEnvelopeRelationshipCandidate(DomainEventEnvelope event) {
        try {
            UUID candidate = UUID.fromString(event.aggregateId());
            return candidate.toString().equals(event.aggregateId()) ? candidate : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public record ValidatedEvent(
            DomainEventEnvelope envelope,
            Xcon004EmploymentChangedV1 body,
            String payloadSha256,
            EmploymentAbsenceProjection.InvalidationEvidence invalidation) {
        public ValidatedEvent {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(body, "body");
            EmploymentAbsenceContract.digest(payloadSha256, "payloadSha256");
            Objects.requireNonNull(invalidation, "invalidation");
        }
    }

    public static final class InvalidEmploymentEvent extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidEmploymentEvent(String message) {
            super(message);
        }

        InvalidEmploymentEvent(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
