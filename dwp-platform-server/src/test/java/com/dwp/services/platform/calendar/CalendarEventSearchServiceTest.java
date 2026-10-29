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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalendarEventSearchServiceTest {

    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime FROM = OffsetDateTime.parse(
            "2026-10-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);

    private CalendarRepository repository;
    private CalendarOccurrenceProjector projector;
    private CalendarEventSearchMetrics metrics;
    private CalendarEventSearchService service;

    @BeforeEach
    void setUp() {
        repository = mock(CalendarRepository.class);
        projector = mock(CalendarOccurrenceProjector.class);
        metrics = mock(CalendarEventSearchMetrics.class);
        WorkplaceRoomAccessPort access = mock(WorkplaceRoomAccessPort.class);
        when(access.viewableResourceIds(anyLong(), anyLong(), any(), anySet()))
                .thenReturn(Set.of());
        service = new CalendarEventSearchService(
                repository, projector, new CalendarRoomAccessGuard(access),
                new CalendarEventSearchCursor(
                        "calendar-event-search-test-secret-at-least-32-bytes", CLOCK),
                metrics, CLOCK);
        when(repository.identityMatches(1L, 7L, PERSON)).thenReturn(true);
        when(repository.visibleEventCandidates(
                eq(1L), eq(7L), eq(PERSON), any(), any(), any(), anyBoolean(),
                any(), any(), eq(500))).thenReturn(List.of());
    }

    @Test
    void ranksThenPagesOccurrencesAndBindsCursorToIdentityQueryAndRange() {
        UUID recurring = UUID.fromString("10000000-0000-0000-0000-000000000010");
        CalendarDtos.EventSummary exact = event(
                recurring, FROM.plusHours(3), FROM.plusHours(1), "Team Sync", null,
                EventDetailLevel.FULL, false, true);
        CalendarDtos.EventSummary description = event(
                recurring, FROM.plusHours(3), FROM.plusHours(2), "Planning",
                "Team Sync agenda", EventDetailLevel.FULL, false, true);
        CalendarDtos.EventSummary hidden = event(
                UUID.randomUUID(), FROM.plusHours(1), null, "Team Sync Secret", null,
                EventDetailLevel.FREE_BUSY, true, false);
        when(projector.summariesForRows(
                eq(1L), eq(7L), eq(PERSON), anyList(), eq(FROM),
                eq(FROM.plusDays(1)), eq("en"), eq(10_000)))
                .thenReturn(List.of(description, hidden, exact));

        CalendarEventSearchDtos.SearchPage first = service.search(
                1L, 7L, PERSON, null, "en", "  TEAM   sync ",
                FROM, FROM.plusDays(1), "UTC", 1, null);

        assertThat(first.items()).containsExactly(exact);
        assertThat(first.normalizedQuery()).isEqualTo("team sync");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.generatedAt().toInstant()).isEqualTo(CLOCK.instant());
        CalendarEventSearchDtos.SearchPage second = service.search(
                1L, 7L, PERSON, null, "en", "team sync",
                FROM, FROM.plusDays(1), "UTC", 1, first.nextCursor());
        assertThat(second.items()).containsExactly(description);
        assertThat(second.hasMore()).isFalse();

        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "different query",
                FROM, FROM.plusDays(1), "UTC", 1, first.nextCursor()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.search(
                1L, 8L, UUID.randomUUID(), null, "en", "team sync",
                FROM, FROM.plusDays(1), "UTC", 1, first.nextCursor()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cursor");
        verify(repository, times(2)).identityMatches(1L, 7L, PERSON);
    }

    @Test
    void rejectsInvalidQueryRangeZoneAndLimitBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "x",
                FROM, FROM.plusDays(1), "UTC", 10, null))
                .isInstanceOf(BaseException.class).hasMessageContaining("2 and 120");
        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "valid",
                FROM, FROM, "UTC", 10, null))
                .isInstanceOf(BaseException.class).hasMessageContaining("valid");
        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "valid",
                FROM, FROM.plusDays(93), "America/New_York", 10, null))
                .isInstanceOf(BaseException.class).hasMessageContaining("92 local days");
        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "valid",
                FROM, FROM.plusDays(1), "Not/AZone", 10, null))
                .isInstanceOf(BaseException.class).hasMessageContaining("time zone");
        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "valid",
                FROM, FROM.plusDays(1), "UTC", 101, null))
                .isInstanceOf(BaseException.class).hasMessageContaining("1 and 100");

        verify(repository, never()).identityMatches(anyLong(), anyLong(), any());
        verify(metrics, times(5)).rejected(anyLong());
    }

    @Test
    void candidateScanStopsAtTenThousandAndRecordsBudgetRejection() {
        List<CalendarRepository.EventRow> fullPage = new ArrayList<>();
        for (int index = 0; index < 500; index++) fullPage.add(row(index));
        when(repository.visibleEventCandidates(
                eq(1L), eq(7L), eq(PERSON), any(), eq(FROM), eq(FROM.plusDays(1)),
                eq(false), any(), any(), eq(500))).thenReturn(fullPage);

        assertThatThrownBy(() -> service.search(
                1L, 7L, PERSON, null, "en", "team",
                FROM, FROM.plusDays(1), "UTC", 100, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("query budget");

        verify(repository, times(21)).visibleEventCandidates(
                eq(1L), eq(7L), eq(PERSON), any(), eq(FROM), eq(FROM.plusDays(1)),
                eq(false), any(), any(), eq(500));
        verifyNoInteractions(projector);
        verify(metrics).budgetExceeded();
        verify(metrics).rejected(anyLong());
    }

    private CalendarDtos.EventSummary event(
            UUID eventId,
            OffsetDateTime startsAt,
            OffsetDateTime recurrenceId,
            String title,
            String description,
            EventDetailLevel detailLevel,
            boolean redacted,
            boolean canViewDetails) {
        return new CalendarDtos.EventSummary(
                eventId, UUID.randomUUID(), "Calendar", "#2563EB", 7L, PERSON,
                "Owner", "owner@example.com", title, description, EventType.MEETING,
                startsAt, startsAt.plusMinutes(30), "UTC", false, null, null,
                EventStatus.CONFIRMED, EventVisibility.DEFAULT, RecurrencePattern.DAILY,
                1, (LocalDate) null, false, null, List.of(), null, false,
                EventImportance.NORMAL, detailLevel, redacted, false, 0,
                new CalendarDtos.EventCapabilities(
                        canViewDetails, false, false, false, false, false),
                redacted ? "FREE_BUSY_ONLY" : null, recurrenceId, 0L);
    }

    private CalendarRepository.EventRow row(int index) {
        return new CalendarRepository.EventRow(
                new UUID(0, index + 1L), UUID.randomUUID(), "Calendar", "#2563EB",
                7L, PERSON, "Owner", "owner@example.com", "Team", null,
                EventType.MEETING, FROM.plusMinutes(index), FROM.plusMinutes(index + 30),
                "UTC", false, null, null, EventStatus.CONFIRMED,
                EventVisibility.DEFAULT, RecurrencePattern.NONE, 1, null, false,
                null, null, EventImportance.NORMAL, EventDetailLevel.FULL, false,
                0L, CalendarAccessLevel.OWNER, 0L, List.of(), null);
    }
}
