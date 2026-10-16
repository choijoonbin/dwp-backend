package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimeHomeEventPublisherTest {

    @Test
    void publishesExactTenantBoundXcon014WithDeterministicIdempotency() {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        when(recorder.record(any())).thenAnswer(invocation ->
                invocation.<DomainEventEnvelope>getArgument(0).id());
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        var publisher = new TimeHomeEventPublisher(
                recorder, contracts, TimeHomeTestFixtures.mapper(), true);
        var materialization = TimeHomeTestFixtures.service(
                Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                (authority, cutoff) -> TimeHomeTestFixtures.projection(),
                TimeHomeTestFixtures.disabledPublisher())
                .prepare(TimeHomeTestFixtures.selfAuthority());

        UUID first = publisher.publish(TimeHomeTestFixtures.TENANT, materialization).orElseThrow();
        UUID duplicate = publisher.publish(
                TimeHomeTestFixtures.TENANT, materialization).orElseThrow();

        ArgumentCaptor<DomainEventEnvelope> events =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder, org.mockito.Mockito.times(2)).record(events.capture());
        assertThat(first).isEqualTo(duplicate);
        assertThat(events.getAllValues()).extracting(DomainEventEnvelope::id)
                .containsExactly(first, first);
        DomainEventEnvelope event = events.getValue();
        assertThat(event.type()).isEqualTo(TimeHomeEventPublisher.EVENT_TYPE);
        assertThat(event.schemaVersion()).isOne();
        assertThat(event.tenantId()).isEqualTo(TimeHomeTestFixtures.TENANT);
        assertThat(event.data().fieldNames()).toIterable().containsExactlyInAnyOrder(
                "ownerPopulationScope", "freshUntil", "generatedAt", "module",
                "payloadDigest", "payloadRef", "scopeRevision", "staleAfter",
                "state", "widgetKey");
        assertThat(event.data().path("module").asText()).isEqualTo("TIM");
        assertThat(event.data().toString()).doesNotContain("location", "leaveReason");
        assertThat(event.aggregateSequence()).isOne();
        assertThat(event.aggregateId()).isEqualTo(event.id().toString());
        assertThat(contracts.snapshot()).containsEntry(
                TimeHomeEventPublisher.EVENT_TYPE,
                new DomainEventContractRegistry.Contract(1, 1));
    }

    @Test
    void publicationIsDefaultOffAndDoesNotTouchTheOutbox() {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        var publisher = new TimeHomeEventPublisher(
                recorder, new DomainEventContractRegistry(),
                TimeHomeTestFixtures.mapper(), false);
        HrisHomeContributionMaterializationV1 materialization = TimeHomeTestFixtures.service(
                Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                (authority, cutoff) -> TimeHomeTestFixtures.projection(),
                TimeHomeTestFixtures.disabledPublisher())
                .prepare(TimeHomeTestFixtures.selfAuthority());

        assertThat(publisher.publish(TimeHomeTestFixtures.TENANT, materialization)).isEmpty();
        verify(recorder, never()).record(any());
    }
}
