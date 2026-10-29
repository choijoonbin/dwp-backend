package com.dwp.services.platform.productivity;

import com.dwp.services.platform.calendar.CalendarExternalDtos;
import com.dwp.services.platform.calendar.CalendarExternalIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductivityCalendarExternalProjectionAdapterTest {

    @Test
    void mapsOnlyReadOnlyDeterministicMicrosoftProjectionFields() {
        ProductivityCalendarProjectionFacade facade = mock(ProductivityCalendarProjectionFacade.class);
        ProductivityCalendarExternalProjectionAdapter adapter =
                new ProductivityCalendarExternalProjectionAdapter(facade);
        UUID connectorId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID itemId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        Instant startsAt = Instant.parse("2026-10-29T01:00:00Z");
        Instant endsAt = Instant.parse("2026-10-29T02:00:00Z");
        Instant lastSuccess = Instant.parse("2026-10-28T23:00:00Z");
        Instant generatedAt = Instant.parse("2026-10-28T23:30:00Z");
        ProductivityCalendarProjectionFacade.Snapshot snapshot =
                new ProductivityCalendarProjectionFacade.Snapshot(
                        List.of(new ProductivityCalendarProjectionFacade.ProjectedEvent(
                                itemId, connectorId, "Team planning",
                                "https://outlook.office.com/calendar/item/2",
                                startsAt, endsAt, "normal", "CONFIDENTIAL")),
                        List.of(new ProductivityCalendarProjectionFacade.SourceEvidence(
                                connectorId, ProductivityCalendarProjectionFacade.Status.HEALTHY,
                                lastSuccess, null, false)),
                        false, false, generatedAt);
        OffsetDateTime from = OffsetDateTime.parse("2026-10-29T00:00:00+09:00");
        OffsetDateTime to = from.plusDays(7);
        CalendarExternalDtos.Query query =
                new CalendarExternalDtos.Query(7L, 42L, from, to, 100);
        when(facade.project(7L, 42L, from.toInstant(), to.toInstant(), 100))
                .thenReturn(snapshot);

        CalendarExternalDtos.Projection first = adapter.project(query);
        CalendarExternalDtos.Projection repeated = adapter.project(query);

        UUID expectedCalendarId = CalendarExternalIds.calendarId(
                7L, 42L, CalendarExternalDtos.Provider.MICROSOFT_365, connectorId);
        UUID expectedEventId = CalendarExternalIds.eventId(expectedCalendarId, itemId);
        assertThat(first.events()).singleElement().satisfies(event -> {
            assertThat(event.externalCalendarId()).isEqualTo(expectedCalendarId);
            assertThat(event.externalEventId()).isEqualTo(expectedEventId);
            assertThat(event.provider()).isEqualTo(CalendarExternalDtos.Provider.MICROSOFT_365);
            assertThat(event.capabilities().readOnly()).isTrue();
            assertThat(event.startsAt().toInstant()).isEqualTo(startsAt);
            assertThat(event.endsAt().toInstant()).isEqualTo(endsAt);
        });
        assertThat(first.sources()).singleElement().satisfies(source -> {
            assertThat(source.externalCalendarId()).isEqualTo(expectedCalendarId);
            assertThat(source.status()).isEqualTo(CalendarExternalDtos.SourceStatus.HEALTHY);
            assertThat(source.lastSuccessfulSyncAt().toInstant()).isEqualTo(lastSuccess);
        });
        assertThat(repeated.events().get(0).externalEventId()).isEqualTo(expectedEventId);
        assertThat(first.partial()).isFalse();
        verify(facade, org.mockito.Mockito.times(2))
                .project(7L, 42L, from.toInstant(), to.toInstant(), 100);
    }

    @Test
    void keepsUnavailableUnconfiguredSourceFailClosedWithoutInventingACalendar() {
        ProductivityCalendarProjectionFacade facade = mock(ProductivityCalendarProjectionFacade.class);
        ProductivityCalendarExternalProjectionAdapter adapter =
                new ProductivityCalendarExternalProjectionAdapter(facade);
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        when(facade.project(7L, 42L, now, now.plusSeconds(3600), 100))
                .thenReturn(new ProductivityCalendarProjectionFacade.Snapshot(
                        List.of(),
                        List.of(new ProductivityCalendarProjectionFacade.SourceEvidence(
                                null, ProductivityCalendarProjectionFacade.Status.UNAVAILABLE,
                                null, "MICROSOFT_CALENDAR_CONNECTOR_NOT_CONFIGURED", false)),
                        true, false, now));

        CalendarExternalDtos.Projection result = adapter.project(new CalendarExternalDtos.Query(
                7L, 42L, OffsetDateTime.parse("2026-10-28T00:00:00Z"),
                OffsetDateTime.parse("2026-10-28T01:00:00Z"), 100));

        assertThat(result.events()).isEmpty();
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.externalCalendarId()).isNull();
            assertThat(source.status()).isEqualTo(CalendarExternalDtos.SourceStatus.UNAVAILABLE);
        });
        assertThat(result.partial()).isTrue();
    }
}
