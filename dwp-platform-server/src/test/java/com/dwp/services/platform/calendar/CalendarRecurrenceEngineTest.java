package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarRecurrenceEngineTest {

    private final CalendarRecurrenceEngine recurrence = new CalendarRecurrenceEngine();

    @Test
    void dailyProjectionKeepsNominalWallTimeAcrossDstGapAndOverlap() {
        OffsetDateTime spring = OffsetDateTime.parse("2026-03-07T02:30:00-05:00");
        List<CalendarRecurrenceEngine.Projection> springValues = recurrence.project(
                spring, spring.plusMinutes(30),
                recurrence.rule(
                        RecurrencePattern.DAILY, 1, null, 3, Set.of(),
                        "America/New_York"),
                spring.minusMinutes(1), spring.plusDays(4), 10);

        assertThat(springValues).extracting(value -> value.startsAt().toString())
                .containsExactly(
                        "2026-03-07T02:30-05:00",
                        "2026-03-08T03:30-04:00",
                        "2026-03-09T02:30-04:00");

        OffsetDateTime fall = OffsetDateTime.parse("2026-10-31T01:30:00-04:00");
        List<CalendarRecurrenceEngine.Projection> fallValues = recurrence.project(
                fall, fall.plusMinutes(30),
                recurrence.rule(
                        RecurrencePattern.DAILY, 1, null, 3, Set.of(),
                        "America/New_York"),
                fall.minusMinutes(1), fall.plusDays(4), 10);

        assertThat(fallValues).extracting(value -> value.startsAt().toString())
                .containsExactly(
                        "2026-10-31T01:30-04:00",
                        "2026-11-01T01:30-04:00",
                        "2026-11-02T01:30-05:00");
    }

    @Test
    void monthlyAndYearlyUseSequentialClamp() {
        OffsetDateTime monthly = OffsetDateTime.parse("2027-01-31T09:00:00+09:00");
        assertThat(recurrence.project(
                monthly, monthly.plusHours(1),
                recurrence.rule(
                        RecurrencePattern.MONTHLY, 1, null, 3, Set.of(), "Asia/Seoul"),
                monthly.minusMinutes(1), monthly.plusMonths(4), 10))
                .extracting(value -> value.startsAt().toLocalDate())
                .containsExactly(
                        LocalDate.of(2027, 1, 31),
                        LocalDate.of(2027, 2, 28),
                        LocalDate.of(2027, 3, 28));

        OffsetDateTime yearly = OffsetDateTime.parse("2024-02-29T09:00:00+09:00");
        assertThat(recurrence.project(
                yearly, yearly.plusHours(1),
                recurrence.rule(
                        RecurrencePattern.YEARLY, 1, null, 5, Set.of(), "Asia/Seoul"),
                yearly.minusMinutes(1), yearly.plusYears(6), 10))
                .extracting(value -> value.startsAt().toLocalDate())
                .containsExactly(
                        LocalDate.of(2024, 2, 29),
                        LocalDate.of(2025, 2, 28),
                        LocalDate.of(2026, 2, 28),
                        LocalDate.of(2027, 2, 28),
                        LocalDate.of(2028, 2, 28));
    }

    @Test
    void weeklyByDayHonorsWeekIntervalAndCount() {
        OffsetDateTime monday = OffsetDateTime.parse("2026-10-05T09:00:00+09:00");
        assertThat(recurrence.project(
                monday, monday.plusMinutes(30),
                recurrence.rule(
                        RecurrencePattern.WEEKLY, 2, null, 5,
                        Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), "Asia/Seoul"),
                monday.minusMinutes(1), monday.plusMonths(2), 10))
                .extracting(value -> value.startsAt().toLocalDate())
                .containsExactly(
                        LocalDate.of(2026, 10, 5),
                        LocalDate.of(2026, 10, 7),
                        LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 21),
                        LocalDate.of(2026, 11, 2));
    }

    @Test
    void rejectsMutuallyExclusiveBoundsInvalidByDayAndProjectionTruncation() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-05T09:00:00+09:00");
        assertThatThrownBy(() -> recurrence.project(
                startsAt, startsAt.plusMinutes(30),
                recurrence.rule(
                        RecurrencePattern.DAILY, 1, LocalDate.of(2026, 10, 10),
                        2, Set.of(), "Asia/Seoul"),
                startsAt, startsAt.plusDays(10), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> recurrence.project(
                startsAt, startsAt.plusMinutes(30),
                recurrence.rule(
                        RecurrencePattern.MONTHLY, 1, null,
                        null, Set.of(DayOfWeek.MONDAY), "Asia/Seoul"),
                startsAt, startsAt.plusMonths(2), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BYDAY");
        assertThatThrownBy(() -> recurrence.project(
                startsAt, startsAt.plusMinutes(30),
                recurrence.rule(
                        RecurrencePattern.DAILY, 1, null,
                        null, Set.of(), "Asia/Seoul"),
                startsAt, startsAt.plusDays(20), 5))
                .isInstanceOf(CalendarRecurrenceEngine.ProjectionLimitExceededException.class);
    }

    @Test
    void fastForwardsUnboundedLegacyDailySeriesWithoutLosingOrdinalOrMembership() {
        OffsetDateTime anchor = OffsetDateTime.parse("2010-01-01T09:00:00+09:00");
        OffsetDateTime windowFrom = OffsetDateTime.parse("2026-10-28T00:00:00+09:00");
        OffsetDateTime windowTo = windowFrom.plusDays(3);
        CalendarRecurrenceEngine.Rule rule = recurrence.rule(
                RecurrencePattern.DAILY, 1, null, null, Set.of(), "Asia/Seoul");

        List<CalendarRecurrenceEngine.Projection> values = recurrence.project(
                anchor, anchor.plusHours(1), rule, windowFrom, windowTo, 10);

        assertThat(values).extracting(CalendarRecurrenceEngine.Projection::startsAt)
                .containsExactly(
                        OffsetDateTime.parse("2026-10-28T09:00:00+09:00"),
                        OffsetDateTime.parse("2026-10-29T09:00:00+09:00"),
                        OffsetDateTime.parse("2026-10-30T09:00:00+09:00"));
        assertThat(values.getFirst().ordinal()).isGreaterThan(4000);
        assertThat(recurrence.contains(
                anchor,
                OffsetDateTime.parse("2026-10-29T09:00:00+09:00"),
                rule,
                10)).isTrue();
        assertThat(recurrence.contains(
                anchor,
                OffsetDateTime.parse("2026-10-29T09:30:00+09:00"),
                rule,
                10)).isFalse();
    }
}
