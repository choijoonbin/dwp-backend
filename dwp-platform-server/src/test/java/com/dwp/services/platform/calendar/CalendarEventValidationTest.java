package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarEventValidationTest {

    private final CalendarRepository repository = mock(CalendarRepository.class);
    private final CalendarEventValidation validation = new CalendarEventValidation(
            repository,
            mock(CalendarOccurrenceProjector.class),
            new CalendarSchedulingHorizon(Clock.fixed(
                    Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)));
    private final UUID resourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(repository.resource(1L, resourceId, false))
                .thenReturn(Optional.of(resource()));
    }

    @Test
    void countBoundedResourceSeriesChecksEveryOccurrence() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");

        assertThat(validation.validateResource(
                1L, resourceId, startsAt, startsAt.plusMinutes(30), null,
                "Asia/Seoul", RecurrencePattern.DAILY, 1, null,
                List.of(), 3, "en")).isEqualTo(resource());

        for (int day = 0; day < 3; day++) {
            verify(repository).resourceConflict(
                    1L, resourceId, startsAt.plusDays(day),
                    startsAt.plusDays(day).plusMinutes(30), null);
        }
    }

    @Test
    void weeklyByDayChecksTheSecondWeekdayForResourceConflicts() {
        OffsetDateTime monday = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");
        OffsetDateTime wednesday = monday.plusDays(2);
        when(repository.resourceConflict(
                1L, resourceId, wednesday, wednesday.plusMinutes(30), null))
                .thenReturn(true);

        assertThatThrownBy(() -> validation.validateResource(
                1L, resourceId, monday, monday.plusMinutes(30), null,
                "Asia/Seoul", RecurrencePattern.WEEKLY, 1, null,
                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 2, "en"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("unavailable");

        verify(repository).resourceConflict(
                1L, resourceId, wednesday, wednesday.plusMinutes(30), null);
    }

    @Test
    void countCannotBypassTheTenantAdvanceHorizon() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-28T10:00:00+09:00");
        when(repository.policy(1L)).thenReturn(new CalendarRepository.PolicyRow(
                1, LocalTime.of(9, 0), LocalTime.of(18, 0),
                30, 5, 480, 5, 0, 300, 360,
                false, true, 0));

        assertThatThrownBy(() -> validation.validateEvent(
                1L, startsAt, startsAt.plusMinutes(30), "Asia/Seoul",
                EventType.FOCUS, null, RecurrencePattern.DAILY, 1,
                null, 7, List.of(), List.of()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("recurrence count");
    }

    private CalendarRepository.ResourceRow resource() {
        return new CalendarRepository.ResourceRow(
                resourceId, "ROOM-1", "Room", "회의실", "Room",
                ResourceType.ROOM, "HQ", "10F", 8, List.of(), "Asia/Seoul",
                false, ResourceState.AVAILABLE, true, 0L);
    }
}
