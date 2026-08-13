package com.dwp.core.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** CloudEvents-aligned envelope used by every DWP domain-event producer and consumer. */
public record DomainEventEnvelope(
        String specVersion,
        UUID id,
        String source,
        String type,
        int schemaVersion,
        Instant time,
        String subject,
        Long tenantId,
        String aggregateType,
        String aggregateId,
        long aggregateSequence,
        String correlationId,
        String causationId,
        String traceParent,
        JsonNode data,
        Map<String, String> extensions) {

    public DomainEventEnvelope {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static DomainEventEnvelope create(
            String source,
            String type,
            int schemaVersion,
            Long tenantId,
            String aggregateType,
            String aggregateId,
            long aggregateSequence,
            String correlationId,
            String causationId,
            String traceParent,
            JsonNode data) {
        return new DomainEventEnvelope(
                "1.0",
                UUID.randomUUID(),
                source,
                type,
                schemaVersion,
                Instant.now(),
                aggregateType + "/" + aggregateId,
                tenantId,
                aggregateType,
                aggregateId,
                aggregateSequence,
                correlationId,
                causationId,
                traceParent,
                Objects.requireNonNull(data, "data"),
                Map.of());
    }
}
