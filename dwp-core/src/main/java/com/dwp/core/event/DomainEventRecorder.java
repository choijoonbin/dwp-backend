package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Appends a validated event to the service-local outbox in the caller transaction. */
public class DomainEventRecorder {

    private final DomainEventOutboxRepository repository;
    private final DomainEventContractRegistry contracts;
    private final ObjectMapper objectMapper;

    public DomainEventRecorder(
            DomainEventOutboxRepository repository,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.contracts = contracts;
        this.objectMapper = objectMapper;
    }

    public UUID record(DomainEventEnvelope event) {
        contracts.requireCompatible(event);
        String payload = DomainEventJson.serialize(objectMapper, event);
        String payloadHash = DomainEventJson.sha256(payload);
        if (!repository.append(event, payload, payloadHash)) {
            String existing = repository.payloadHash(event.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Domain-event idempotency check lost its persisted event."));
            if (!existing.equals(payloadHash)) {
                throw new IllegalStateException(
                        "A domain-event id was reused with a different payload: " + event.id());
            }
        }
        return event.id();
    }
}
