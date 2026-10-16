package com.dwp.services.people.hris.performance.home;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PerformanceHomeEventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsClosedXcon012WithDeterministicIdThroughSharedOutbox() {
        PerformanceHomeProjectionPort projections = mock(PerformanceHomeProjectionPort.class);
        when(projections.load(PerformanceHomeTestFixtures.authority()))
                .thenReturn(PerformanceHomeTestFixtures.snapshot());
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        PerformanceHomeEventPublisher publisher = new PerformanceHomeEventPublisher(
                projections, PerformanceHomeTestFixtures.materializer(), recorder,
                contracts, mapper, true);

        var first = publisher.publish(
                PerformanceHomeTestFixtures.authority(),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000901"))
                .orElseThrow();
        var replay = publisher.publish(
                PerformanceHomeTestFixtures.authority(),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000901"))
                .orElseThrow();

        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(replay.event()).isEqualTo(first.event());
        assertThat(first.event().module()).isEqualTo("PER");
        assertThat(first.event().payloadDigest())
                .isEqualTo(first.materialization().payloadDigest());
        assertThat(first.event().payloadRef())
                .isEqualTo(first.materialization().payloadRef());
        assertThat(contracts.snapshot()).containsKey(PerformanceHomeEventPublisher.EVENT_TYPE);

        ArgumentCaptor<DomainEventEnvelope> events =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder, times(2)).record(events.capture());
        assertThat(events.getAllValues()).extracting(DomainEventEnvelope::id)
                .containsOnly(first.eventId());
        assertThat(events.getAllValues()).extracting(DomainEventEnvelope::type)
                .containsOnly(PerformanceHomeEventPublisher.EVENT_TYPE);
        assertThat(events.getValue().data()).isEqualTo(mapper.valueToTree(first.event()));
        assertThat(events.getValue().data().size()).isEqualTo(10);
        assertThat(events.getValue().aggregateSequence()).isOne();
        assertThat(events.getValue().aggregateId())
                .isEqualTo(events.getValue().id().toString());
    }

    @Test
    void eventPublicationIsARealDefaultOffGate() {
        PerformanceHomeProjectionPort projections = mock(PerformanceHomeProjectionPort.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        PerformanceHomeEventPublisher publisher = new PerformanceHomeEventPublisher(
                projections, PerformanceHomeTestFixtures.materializer(), recorder,
                new DomainEventContractRegistry(), mapper, false);

        Optional<PerformanceHomeEventPublisher.Publication> result = publisher.publish(
                PerformanceHomeTestFixtures.authority(),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000902"));

        assertThat(result).isEmpty();
        verifyNoInteractions(projections, recorder);
    }
}
