package com.dwp.services.platform.workplace;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkplaceDomainEventsTest {

    @Test
    void recordsAContractValidatedVersionedBookingEvent() {
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        WorkplaceDomainEvents events =
                new WorkplaceDomainEvents(recorder, contracts, new ObjectMapper());
        UUID bookingId = UUID.randomUUID();

        events.bookingChanged(
                WorkplaceDomainEvents.RELOCATED,
                9L,
                "corr-9",
                new WorkplaceDomainEvents.BookingEvent(
                        bookingId,
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "RESERVED",
                        OffsetDateTime.parse("2026-08-20T09:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-20T10:00:00+09:00"),
                        "USER_RELOCATED",
                        3));

        ArgumentCaptor<DomainEventEnvelope> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(envelope.capture());
        assertThat(contracts.requireCompatible(envelope.getValue()).maximumVersion()).isEqualTo(1);
        assertThat(envelope.getValue().type()).isEqualTo(WorkplaceDomainEvents.RELOCATED);
        assertThat(envelope.getValue().aggregateId()).isEqualTo(bookingId.toString());
        assertThat(envelope.getValue().aggregateSequence()).isEqualTo(4);
        assertThat(envelope.getValue().data().path("reasonCode").asText())
                .isEqualTo("USER_RELOCATED");
    }
}
