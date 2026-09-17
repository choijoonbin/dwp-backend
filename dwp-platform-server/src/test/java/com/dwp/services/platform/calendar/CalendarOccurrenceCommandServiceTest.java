package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarOccurrenceCommandServiceTest {

    private final CalendarRepository repository = mock(CalendarRepository.class);
    private final CalendarOccurrenceRepository occurrenceRepository =
            mock(CalendarOccurrenceRepository.class);
    private final CalendarOccurrenceProjector projector =
            new CalendarOccurrenceProjector(repository, occurrenceRepository);
    private final CalendarOccurrenceCommandService service =
            new CalendarOccurrenceCommandService(
                    repository,
                    occurrenceRepository,
                    projector,
                    new CalendarSchedulingHorizon(Clock.fixed(
                            Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC)));

    private final UUID eventId = UUID.randomUUID();
    private final OffsetDateTime first = OffsetDateTime.parse("2026-09-21T09:00:00+09:00");
    private CalendarRepository.EventRow before;
    private CalendarRepository.EventRow updated;

    @BeforeEach
    void setUp() {
        before = CalendarOccurrenceProjectorTest.event(eventId, first, 4L);
        updated = CalendarOccurrenceProjectorTest.event(eventId, first, 5L);
        when(repository.policy(1L)).thenReturn(new CalendarRepository.PolicyRow(
                1, LocalTime.of(9, 0), LocalTime.of(18, 0),
                30, 5, 1440, 365, 0, 300, 360,
                false, true, 0));
        when(repository.attendees(1L, eventId)).thenReturn(List.of());
    }

    @Test
    void occurrenceEditIsVersionedIdempotentAndReturnsTheMaterializedProjection() {
        UUID key = UUID.randomUUID();
        OffsetDateTime original = first.plusDays(1);
        CalendarDtos.UpdateEventRequest request = request(original, key, null);
        CalendarOccurrenceRepository.OverrideRow override =
                new CalendarOccurrenceRepository.OverrideRow(
                        eventId, original, "MODIFIED",
                        request.startsAt(), request.endsAt(), request.title(),
                        request.description(), request.type(), request.allDay(),
                        request.location(), request.conferenceUrl(), request.visibility(),
                        request.responseRequired(), request.importance(), 0L);
        when(repository.event(1L, 7L, null, eventId, false))
                .thenReturn(Optional.of(before), Optional.of(updated), Optional.of(updated));
        when(occurrenceRepository.receipt(1L, 7L, key)).thenReturn(Optional.empty());
        when(occurrenceRepository.advanceEventVersion(1L, eventId, 4L, 7L)).thenReturn(1);
        when(occurrenceRepository.upsertModified(1L, 7L, eventId, original, request))
                .thenReturn(0L);
        when(occurrenceRepository.override(1L, eventId, original))
                .thenReturn(Optional.of(override));

        CalendarDtos.EventSummary firstResult = service.updateOccurrence(
                1L, 7L, null, eventId, "en", "correlation", null, request);

        assertThat(firstResult.title()).isEqualTo("Only this review");
        assertThat(firstResult.recurrenceId()).isEqualTo(original);
        assertThat(firstResult.version()).isEqualTo(5L);
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(occurrenceRepository).saveReceipt(
                eq(1L), eq(7L), eq(key), eq(eventId), eq(original),
                fingerprint.capture(), eq(5L), eq(0L));
        when(occurrenceRepository.receipt(1L, 7L, key)).thenReturn(Optional.of(
                new CalendarOccurrenceRepository.CommandReceipt(
                        eventId, original, fingerprint.getValue(), 5L, 0L)));

        CalendarDtos.EventSummary replay = service.updateOccurrence(
                1L, 7L, null, eventId, "en", "correlation", null, request);

        assertThat(replay.title()).isEqualTo("Only this review");
        verify(occurrenceRepository, times(1))
                .advanceEventVersion(1L, eventId, 4L, 7L);
        verify(occurrenceRepository, times(1))
                .upsertModified(1L, 7L, eventId, original, request);
    }

    @Test
    void occurrenceEditRejectsResourceBackedSeriesWithoutMutatingAnything() {
        CalendarRepository.ResourceRow resource = new CalendarRepository.ResourceRow(
                UUID.randomUUID(), "ROOM-1", "Room", "회의실", "Room",
                ResourceType.ROOM, "HQ", "10F", 8, List.of(), "Asia/Seoul",
                false, ResourceState.AVAILABLE, true, 0L);
        before = new CalendarRepository.EventRow(
                before.eventId(), before.calendarId(), before.calendarName(), before.calendarColor(),
                before.organizerUserId(), before.organizerPersonPublicId(), before.organizerName(),
                before.organizerEmail(), before.title(), before.description(), before.type(),
                before.startsAt(), before.endsAt(), before.timeZone(), before.allDay(),
                before.location(), before.conferenceUrl(), before.status(), before.visibility(),
                before.recurrence(), before.recurrenceInterval(), before.recurrenceUntil(),
                before.responseRequired(), before.myResponse(), resource, before.importance(),
                before.detailLevel(), before.starred(), before.preferenceVersion(),
                before.accessLevel(), before.version());
        when(repository.event(1L, 7L, null, eventId, false)).thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.updateOccurrence(
                1L, 7L, null, eventId, "en", null, null,
                request(first.plusDays(1), UUID.randomUUID(), resource.resourceId())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("series");
        verify(occurrenceRepository, never()).advanceEventVersion(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void seriesScheduleChangeDiscardsOccurrenceOverrides() {
        when(occurrenceRepository.deleteOverrides(1L, eventId)).thenReturn(2);

        assertThat(service.discardOverridesAfterSeriesScheduleChange(1L, eventId)).isEqualTo(2);

        verify(occurrenceRepository).deleteOverrides(1L, eventId);
    }

    private CalendarDtos.UpdateEventRequest request(
            OffsetDateTime original,
            UUID idempotencyKey,
            UUID resourceId) {
        return new CalendarDtos.UpdateEventRequest(
                "Only this review", "Changed agenda", EventType.MEETING,
                original.plusHours(2), original.plusHours(3), "Asia/Seoul", false,
                "Focus room", null, EventVisibility.PRIVATE,
                RecurrencePattern.DAILY, 1, LocalDate.of(2026, 9, 30),
                true, List.of(), resourceId, 4L, EventImportance.HIGH,
                CalendarDtos.RecurrenceEditScope.THIS_OCCURRENCE,
                original, idempotencyKey);
    }
}
