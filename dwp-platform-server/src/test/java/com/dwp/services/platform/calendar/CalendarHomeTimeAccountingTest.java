package com.dwp.services.platform.calendar;

import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarHomeTimeAccountingTest {

    private static final UUID PERSON = UUID.fromString("831ce576-cd30-4e87-9a13-01364f68ef15");
    private final CalendarRepository repository = mock(CalendarRepository.class);
    private final CalendarService service = new CalendarService(
            repository, mock(WorkplaceRoomAccessPort.class),
            new CalendarSchedulingHorizon(Clock.systemUTC()), mock(RoomBookingPolicyService.class));

    @Test
    void overnightMeetingsAndFocusAreSplitAcrossLocalDates() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of(
                event(EventType.MEETING, "2026-09-02T22:00+09:00", "2026-09-03T02:00+09:00"),
                event(EventType.FOCUS, "2026-09-03T23:00+09:00", "2026-09-04T01:00+09:00")));

        assertThat(day(home, "2026-09-02").meetingMinutes()).isEqualTo(120);
        assertThat(day(home, "2026-09-03").meetingMinutes()).isEqualTo(120);
        assertThat(day(home, "2026-09-03").focusMinutes()).isEqualTo(60);
        assertThat(day(home, "2026-09-04").focusMinutes()).isEqualTo(60);
        assertThat(home.metrics().meetingMinutes()).isEqualTo(240);
        assertThat(home.metrics().focusMinutes()).isEqualTo(120);
        assertWeeklyTotals(home);
    }

    @Test
    void bothWeekEdgesAreClippedAndCoachUsesTheSameFocusTotal() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of(
                event(EventType.FOCUS, "2026-08-30T23:00+09:00", "2026-08-31T01:00+09:00"),
                event(EventType.MEETING, "2026-09-06T23:00+09:00", "2026-09-07T01:00+09:00")));

        assertThat(home.metrics().eventCount()).isEqualTo(2);
        assertThat(home.metrics().focusMinutes()).isEqualTo(60);
        assertThat(home.metrics().meetingMinutes()).isEqualTo(60);
        assertThat(day(home, "2026-08-31").focusMinutes()).isEqualTo(60);
        assertThat(day(home, "2026-09-06").meetingMinutes()).isEqualTo(60);
        assertThat(home.attention()).filteredOn(item -> item.key().equals("focus"))
                .singleElement().extracting(CalendarDtos.AttentionItem::description)
                .isEqualTo("Protect 540 more minutes this week.");
        assertWeeklyTotals(home);
    }

    @Test
    void meetingLimitPercentageRemainsAccurateAboveOneHundredAndSixty() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of(
                event(EventType.MEETING, "2026-09-02T09:00+09:00", "2026-09-02T14:00+09:00"),
                event(EventType.MEETING, "2026-09-02T14:00+09:00", "2026-09-02T19:00+09:00")));

        assertThat(day(home, "2026-09-02").meetingMinutes()).isEqualTo(600);
        assertThat(day(home, "2026-09-02").loadPercent()).isEqualTo(200);
    }

    @Test
    void overlappingEventsStillMeasurePerEventWorkloadRatherThanFreeBusyUnion() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of(
                event(EventType.MEETING, "2026-09-02T09:00+09:00", "2026-09-02T11:00+09:00"),
                event(EventType.MEETING, "2026-09-02T10:00+09:00", "2026-09-02T12:00+09:00")));

        assertThat(day(home, "2026-09-02").meetingMinutes()).isEqualTo(240);
        assertThat(day(home, "2026-09-02").loadPercent()).isEqualTo(80);
        assertThat(home.metrics().conflictCount()).isEqualTo(2);
        assertWeeklyTotals(home);
    }

    @Test
    void localDateBoundsAreHalfOpen() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of(
                event(EventType.MEETING, "2026-09-01T23:30+09:00", "2026-09-02T00:00+09:00"),
                event(EventType.MEETING, "2026-09-02T00:00+09:00", "2026-09-02T00:30+09:00"),
                event(EventType.MEETING, "2026-09-02T23:30+09:00", "2026-09-03T00:00+09:00"),
                event(EventType.MEETING, "2026-09-03T00:00+09:00", "2026-09-03T00:30+09:00")));

        assertThat(home.today()).hasSize(2);
        assertThat(day(home, "2026-09-02").eventCount()).isEqualTo(2);
        assertThat(day(home, "2026-09-02").meetingMinutes()).isEqualTo(60);
        assertWeeklyTotals(home);
    }

    @ParameterizedTest
    @CsvSource({"2026-03-08,1380,10020", "2026-11-01,1500,10140"})
    void daylightSavingDaysAndWeeksUseLocalMidnightBoundaries(
            String date, int expectedDayMinutes, int expectedWeekMinutes) {
        ZoneId zone = ZoneId.of("America/New_York");
        LocalDate sunday = LocalDate.parse(date);
        OffsetDateTime weekStart = sunday.minusDays(6).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime weekEnd = sunday.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime lateSunday = sunday.atTime(23, 30).atZone(zone).toOffsetDateTime();
        CalendarRepository.EventRow nextWeek = event(
                EventType.MEETING, weekEnd.plusMinutes(30).toString(), weekEnd.plusHours(1).toString());
        CalendarDtos.HomeResponse home = homeAt(date, zone.getId(), 1, List.of(
                event(EventType.FOCUS, weekStart.toString(), weekEnd.toString()),
                event(EventType.MEETING, lateSunday.toString(), weekEnd.plusMinutes(30).toString()),
                nextWeek));

        assertThat(home.today()).hasSize(2);
        assertThat(home.today()).noneMatch(event -> event.eventId().equals(nextWeek.eventId()));
        assertThat(home.metrics().eventCount()).isEqualTo(2);
        assertThat(day(home, date).focusMinutes()).isEqualTo(expectedDayMinutes);
        assertThat(day(home, date).meetingMinutes()).isEqualTo(30);
        assertThat(home.metrics().focusMinutes()).isEqualTo(expectedWeekMinutes);
        assertThat(home.metrics().meetingMinutes()).isEqualTo(30);
        verify(repository).visibleEvents(eq(1L), eq(7L), eq(PERSON), eq(weekStart), any(), eq(false));
        assertWeeklyTotals(home);
    }

    @Test
    void recurringOvernightOccurrencesAreEachClippedAfterExpansion() {
        CalendarRepository.EventRow recurring = new CalendarRepository.EventRow(
                UUID.randomUUID(), UUID.randomUUID(), "Calendar", "#2563EB", 7L, PERSON,
                "Member", "member@example.test", "Meeting", "Agenda", EventType.MEETING,
                OffsetDateTime.parse("2026-08-31T23:00+09:00"),
                OffsetDateTime.parse("2026-09-01T01:00+09:00"), "Asia/Seoul", false, null, null,
                EventStatus.CONFIRMED, EventVisibility.DEFAULT, RecurrencePattern.DAILY,
                1, LocalDate.parse("2026-09-02"), false, null, null, 0L);
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of(recurring));

        assertThat(home.metrics().eventCount()).isEqualTo(3);
        assertThat(home.metrics().meetingMinutes()).isEqualTo(360);
        assertThat(home.weekLoad()).extracting(CalendarDtos.DayLoad::meetingMinutes)
                .containsExactly(60, 120, 120, 60, 0, 0, 0);
        assertWeeklyTotals(home);
    }

    @Test
    void tenantSundayWeekStartIsRespected() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 7, List.of(
                event(EventType.FOCUS, "2026-08-29T23:00+09:00", "2026-08-30T01:00+09:00"),
                event(EventType.MEETING, "2026-09-05T23:00+09:00", "2026-09-06T01:00+09:00")));

        assertThat(home.weekLoad().getFirst().date()).isEqualTo(LocalDate.parse("2026-08-30"));
        assertThat(home.weekLoad().getLast().date()).isEqualTo(LocalDate.parse("2026-09-05"));
        assertThat(home.metrics().focusMinutes()).isEqualTo(60);
        assertThat(home.metrics().meetingMinutes()).isEqualTo(60);
        assertWeeklyTotals(home);
    }

    @Test
    void emptyHomeHasZeroMinutesAndZeroLoad() {
        CalendarDtos.HomeResponse home = homeAt("2026-09-02", "Asia/Seoul", 1, List.of());

        assertThat(home.metrics().eventCount()).isZero();
        assertThat(home.metrics().focusMinutes()).isZero();
        assertThat(home.metrics().meetingMinutes()).isZero();
        assertThat(home.weekLoad()).hasSize(7).allSatisfy(day -> {
            assertThat(day.eventCount()).isZero();
            assertThat(day.loadPercent()).isZero();
        });
    }

    private CalendarDtos.HomeResponse homeAt(
            String date, String timeZone, int weekStart, List<CalendarRepository.EventRow> events) {
        ZoneId zone = ZoneId.of(timeZone);
        ZonedDateTime now = LocalDate.parse(date).atTime(12, 0).atZone(zone);
        when(repository.policy(1L)).thenReturn(new CalendarRepository.PolicyRow(
                weekStart, LocalTime.of(9, 0), LocalTime.of(18, 0),
                30, 15, 480, 365, 10, 600, 300, false, true, 0L));
        when(repository.visibleEvents(eq(1L), eq(7L), eq(PERSON), any(), any(), eq(false)))
                .thenReturn(events);
        try (MockedStatic<ZonedDateTime> time = mockStatic(ZonedDateTime.class, CALLS_REAL_METHODS)) {
            time.when(() -> ZonedDateTime.now(zone)).thenReturn(now);
            return service.home(1L, 7L, PERSON, timeZone, "en-US");
        }
    }

    private CalendarRepository.EventRow event(EventType type, String startsAt, String endsAt) {
        return new CalendarRepository.EventRow(
                UUID.randomUUID(), UUID.randomUUID(), "Calendar", "#2563EB", 7L, PERSON,
                "Member", "member@example.test", "Event", "Agenda", type,
                OffsetDateTime.parse(startsAt), OffsetDateTime.parse(endsAt), "Asia/Seoul",
                false, null, null, EventStatus.CONFIRMED, EventVisibility.DEFAULT,
                RecurrencePattern.NONE, 1, null, false, null, null, 0L);
    }

    private CalendarDtos.DayLoad day(CalendarDtos.HomeResponse home, String date) {
        return home.weekLoad().stream().filter(day -> day.date().toString().equals(date))
                .findFirst().orElseThrow();
    }

    private void assertWeeklyTotals(CalendarDtos.HomeResponse home) {
        assertThat(home.weekLoad().stream().mapToInt(CalendarDtos.DayLoad::meetingMinutes).sum())
                .isEqualTo(home.metrics().meetingMinutes());
        assertThat(home.weekLoad().stream().mapToInt(CalendarDtos.DayLoad::focusMinutes).sum())
                .isEqualTo(home.metrics().focusMinutes());
    }
}
