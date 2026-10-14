package com.dwp.services.payroll.input.compensationplan;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputTestFixtures.SNAPSHOT_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompensationPlanInputBrokerConsumerTest {

    @Test
    void decodesTheExactBrokerEnvelopeAndRejectsUnknownWireFields() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var canonicalizer = new CompensationPlanInputCanonicalizer(mapper);
        var fixture = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        AtomicReference<DomainEventEnvelope> received = new AtomicReference<>();
        CompensationPlanInputEventHandler handler = envelope -> {
            received.set(envelope);
            return new CompensationPlanInputContracts.ConsumptionResult(
                    CompensationPlanInputContracts.Outcome.QUARANTINED,
                    envelope.id(), null, fixture.snapshot().snapshotId(),
                    fixture.snapshot().snapshotRevision(), 0, "TEST");
        };
        var consumer = new CompensationPlanInputBrokerConsumer(handler, mapper);
        String payload = mapper.writeValueAsString(fixture.envelope());

        consumer.accept(payload);
        assertThat(received.get().id()).isEqualTo(fixture.envelope().id());
        assertThat(received.get().tenantId()).isEqualTo(fixture.envelope().tenantId());
        assertThat(received.get().aggregateSequence())
                .isEqualTo(fixture.envelope().aggregateSequence());
        assertThat(mapper.writeValueAsString(received.get().data()))
                .isEqualTo(mapper.writeValueAsString(fixture.envelope().data()));

        String hostile = payload.substring(0, payload.length() - 1)
                + ",\"callerTenantOverride\":999}";
        assertThatThrownBy(() -> consumer.accept(hostile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical JSON");

        String duplicate = payload.replaceFirst(
                "\\{", "{\"specVersion\":\"1.0\",");
        assertThatThrownBy(() -> consumer.accept(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical JSON");
    }
}
