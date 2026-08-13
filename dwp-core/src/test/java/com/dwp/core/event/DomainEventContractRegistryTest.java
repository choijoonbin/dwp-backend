package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventContractRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsOnlyRegisteredCompatibleSchemaVersions() {
        DomainEventContractRegistry registry = new DomainEventContractRegistry();
        registry.register("dwp.people.worker-changed", 1, 2);

        assertThat(registry.requireCompatible(event(2)).maximumVersion()).isEqualTo(2);
        assertThatThrownBy(() -> registry.requireCompatible(event(3)))
                .isInstanceOf(DomainEventContractRegistry.UnsupportedEventContractException.class)
                .hasMessageContaining("supported 1-2");
    }

    @Test
    void rejectsInvalidTraceContextAndConflictingRegistration() {
        DomainEventContractRegistry registry = new DomainEventContractRegistry();
        registry.register("dwp.people.worker-changed", 1, 1);

        DomainEventEnvelope malformed = new DomainEventEnvelope(
                "1.0", UUID.randomUUID(), "dwp-people-server",
                "dwp.people.worker-changed", 1, Instant.now(), "worker/7",
                1L, "WORKER", "7", 1, "corr-7", null,
                "not-a-traceparent", objectMapper.createObjectNode(), Map.of());

        assertThatThrownBy(() -> registry.requireCompatible(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceparent");
        assertThatThrownBy(() -> registry.register("dwp.people.worker-changed", 1, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    private DomainEventEnvelope event(int schemaVersion) {
        return new DomainEventEnvelope(
                "1.0", UUID.randomUUID(), "dwp-people-server",
                "dwp.people.worker-changed", schemaVersion, Instant.now(), "worker/7",
                1L, "WORKER", "7", 1, "corr-7", null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                objectMapper.createObjectNode().put("workerId", 7), Map.of());
    }
}
