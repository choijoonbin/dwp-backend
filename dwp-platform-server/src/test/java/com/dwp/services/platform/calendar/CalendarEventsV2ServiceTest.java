package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarEventsV2ServiceTest {

    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime FROM = OffsetDateTime.parse(
            "2026-10-28T00:00:00Z");

    private CalendarRepository repository;
    private CalendarOccurrenceProjector projector;
    private CalendarEventsV2Service service;

    @BeforeEach
    void setUp() {
        repository = mock(CalendarRepository.class);
        projector = mock(CalendarOccurrenceProjector.class);
        WorkplaceRoomAccessPort roomAccessPort = mock(WorkplaceRoomAccessPort.class);
        when(roomAccessPort.viewableResourceIds(anyLong(), anyLong(), any(), anySet()))
                .thenReturn(Set.of());
        CalendarRoomAccessGuard roomAccess = new CalendarRoomAccessGuard(roomAccessPort);
        CalendarEventsV2Cursor cursor = new CalendarEventsV2Cursor(
                "calendar-events-v2-test-secret-at-least-32-bytes",
                Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
        CalendarEventsV2Metrics metrics = mock(CalendarEventsV2Metrics.class);
        service = new CalendarEventsV2Service(
                repository, projector, roomAccess, cursor, metrics,
                Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
        when(repository.identityMatches(1L, 7L, PERSON)).thenReturn(true);
        when(repository.visibleEventCandidates(
                eq(1L), eq(7L), eq(PERSON), any(), any(), any(), anyBoolean(),
                any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void stableCursorPagesByStartEventAndRecurrenceAndBindsIdentity() {
        CalendarDtos.EventSummary first = event(
                UUID.fromString("10000000-0000-0000-0000-000000000010"), FROM.plusHours(1));
        CalendarDtos.EventSummary second = event(
                UUID.fromString("10000000-0000-0000-0000-000000000020"), FROM.plusHours(1));
        when(projector.summariesForRows(
                eq(1L), eq(7L), eq(PERSON), anyList(), eq(FROM),
                eq(FROM.plusDays(1)), eq("en"), eq(10_000)))
                .thenReturn(List.of(second, first));

        CalendarEventsV2Dtos.EventPage page = service.events(
                1L, 7L, PERSON, null, "en", FROM, FROM.plusDays(1), "UTC", 1, null);

        assertThat(page.items()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        CalendarEventsV2Dtos.EventPage next = service.events(
                1L, 7L, PERSON, null, "en", FROM, FROM.plusDays(1),
                "UTC", 1, page.nextCursor());
        assertThat(next.items()).containsExactly(second);
        assertThat(next.hasMore()).isFalse();
        assertThatThrownBy(() -> service.events(
                1L, 8L, UUID.randomUUID(), null, "en", FROM, FROM.plusDays(1),
                "UTC", 1, page.nextCursor()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsMoreThanNinetyTwoLocalDaysAndPageSizeAboveTwoHundred() {
        assertThatThrownBy(() -> service.events(
                1L, 7L, PERSON, null, "en", FROM,
                FROM.plusDays(93), "America/New_York", 100, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("92 local days");
        assertThatThrownBy(() -> service.events(
                1L, 7L, PERSON, null, "en", FROM,
                FROM.plusDays(1), "UTC", 201, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("between 1 and 200");
    }

    @Test
    void projectionBudgetStopsBeforeAttendeeMaterialization() {
        List<CalendarRepository.EventRow> candidates = new java.util.ArrayList<>();
        for (int index = 0; index < 109; index++) {
            candidates.add(recurringRow(index));
        }
        when(repository.visibleEventCandidates(
                eq(1L), eq(7L), eq(PERSON), any(), eq(FROM), eq(FROM.plusDays(92)),
                eq(false), any(), any(), eq(500))).thenReturn(candidates);
        CalendarOccurrenceProjector boundedProjector =
                new CalendarOccurrenceProjector(repository);
        WorkplaceRoomAccessPort access = mock(WorkplaceRoomAccessPort.class);
        CalendarEventsV2Service bounded = new CalendarEventsV2Service(
                repository, boundedProjector, new CalendarRoomAccessGuard(access),
                new CalendarEventsV2Cursor(
                        "calendar-events-v2-test-secret-at-least-32-bytes",
                        Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)),
                mock(CalendarEventsV2Metrics.class),
                Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> bounded.events(
                1L, 7L, PERSON, null, "en", FROM, FROM.plusDays(92),
                "UTC", 200, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("query budget");

        verify(repository, never()).attendeesByEvents(anyLong(), anyList());
        verify(access, never()).viewableResourceIds(anyLong(), anyLong(), any(), anySet());
    }

    private CalendarDtos.EventSummary event(UUID eventId, OffsetDateTime startsAt) {
        return new CalendarDtos.EventSummary(
                eventId, UUID.randomUUID(), "Calendar", "#2563EB", 7L, PERSON,
                "Owner", "owner@example.com", "Event", null, EventType.MEETING,
                startsAt, startsAt.plusMinutes(30), "UTC", false, null, null,
                EventStatus.CONFIRMED, EventVisibility.DEFAULT, RecurrencePattern.NONE,
                1, (LocalDate) null, false, null, List.of(), null, false, 0L);
    }

    private CalendarRepository.EventRow recurringRow(int index) {
        UUID eventId = new UUID(0L, index + 1L);
        return new CalendarRepository.EventRow(
                eventId, UUID.randomUUID(), "Calendar", "#2563EB", 7L, PERSON,
                "Owner", "owner@example.com", "Event", null, EventType.MEETING,
                FROM, FROM.plusMinutes(30), "UTC", false, null, null,
                EventStatus.CONFIRMED, EventVisibility.DEFAULT, RecurrencePattern.DAILY,
                1, null, false, null, null, EventImportance.NORMAL,
                EventDetailLevel.FULL, false, 0L, CalendarAccessLevel.OWNER,
                0L, List.of(), null);
    }
}
