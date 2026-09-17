package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CalendarOccurrenceProjectorTest {

    @Test
    void visibleEventsApplyMovedAndCancelledOccurrenceOverrides() {
        CalendarRepository repository = mock(CalendarRepository.class);
        CalendarOccurrenceRepository occurrenceRepository = mock(CalendarOccurrenceRepository.class);
        CalendarOccurrenceProjector projector = new CalendarOccurrenceProjector(
                repository, occurrenceRepository);
        UUID eventId = UUID.randomUUID();
        OffsetDateTime first = OffsetDateTime.parse("2026-09-21T09:00:00+09:00");
        CalendarRepository.EventRow row = event(eventId, first, 4L);
        OffsetDateTime second = first.plusDays(1);
        OffsetDateTime third = first.plusDays(2);
        CalendarOccurrenceRepository.OverrideRow moved = new CalendarOccurrenceRepository.OverrideRow(
                eventId, second, "MODIFIED", second.plusHours(2), second.plusHours(3),
                "Moved review", "Changed agenda", EventType.MEETING, false,
                "Focus room", null, EventVisibility.PRIVATE, true,
                EventImportance.HIGH, 1L);
        CalendarOccurrenceRepository.OverrideRow cancelled =
                new CalendarOccurrenceRepository.OverrideRow(
                        eventId, third, "CANCELLED", null, null,
                        null, null, null, null, null, null, null, null, null, 0L);
        when(repository.visibleEvents(
                eq(1L), eq(7L), any(), any(OffsetDateTime.class),
                any(OffsetDateTime.class), eq(false))).thenReturn(List.of(row));
        when(repository.attendees(1L, eventId)).thenReturn(List.of());
        when(occurrenceRepository.overrides(
                eq(1L), eq(List.of(eventId)), any(), any(), anyInt()))
                .thenReturn(List.of(moved, cancelled));

        List<CalendarDtos.EventSummary> values = projector.summaries(
                1L, 7L, null, first.minusHours(1), first.plusDays(3), "en");

        assertThat(values).hasSize(2);
        assertThat(values.get(0).startsAt()).isEqualTo(first);
        assertThat(values.get(0).recurrenceId()).isEqualTo(first);
        assertThat(values.get(1).title()).isEqualTo("Moved review");
        assertThat(values.get(1).startsAt()).isEqualTo(second.plusHours(2));
        assertThat(values.get(1).recurrenceId()).isEqualTo(second);
        assertThat(values.get(1).importance()).isEqualTo(EventImportance.HIGH);
    }

    static CalendarRepository.EventRow event(
            UUID eventId,
            OffsetDateTime startsAt,
            long version) {
        return new CalendarRepository.EventRow(
                eventId, UUID.randomUUID(), "My calendar", "#2563EB",
                7L, null, "Owner", "owner@example.com",
                "Daily review", "Agenda", EventType.MEETING,
                startsAt, startsAt.plusHours(1), "Asia/Seoul", false,
                null, null, EventStatus.CONFIRMED, EventVisibility.DEFAULT,
                RecurrencePattern.DAILY, 1, LocalDate.of(2026, 9, 30),
                true, null, null, version);
    }
}
