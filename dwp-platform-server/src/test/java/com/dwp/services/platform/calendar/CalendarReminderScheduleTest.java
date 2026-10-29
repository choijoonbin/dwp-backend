package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;

import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarReminderScheduleTest {

    @Test
    void dailyRecurrencePreservesLocalWallClockAcrossSpringAndFallDst() {
        var spring = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2026-03-07T09:00:00-05:00"),
                "America/New_York", DAILY, 1, LocalDate.parse("2026-03-10"));
        var springNext = CalendarReminderSchedule.next(
                spring, 30, OffsetDateTime.parse("2026-03-07T14:00:00Z"));
        assertThat(springNext.startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-03-08T09:00:00-04:00"));
        assertThat(springNext.dueAt())
                .isEqualTo(OffsetDateTime.parse("2026-03-08T08:30:00-04:00"));

        var fall = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2026-10-31T09:00:00-04:00"),
                "America/New_York", DAILY, 1, LocalDate.parse("2026-11-03"));
        var fallNext = CalendarReminderSchedule.next(
                fall, 0, OffsetDateTime.parse("2026-10-31T13:00:00.000000001Z"));
        assertThat(fallNext.startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-11-01T09:00:00-05:00"));
    }

    @Test
    void monthlyProjectionUsesIncrementalMonthEndSemanticsAndHonorsInclusiveUntilDate() {
        var schedule = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2026-01-31T09:00:00+09:00"),
                "Asia/Seoul", MONTHLY, 1, LocalDate.parse("2026-03-31"));

        assertThat(CalendarReminderSchedule.next(
                schedule, 60, OffsetDateTime.parse("2026-02-01T00:00:00Z")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-02-28T09:00:00+09:00"));
        assertThat(CalendarReminderSchedule.next(
                schedule, 60, OffsetDateTime.parse("2026-03-01T00:00:00Z")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-03-28T09:00:00+09:00"));
        assertThat(CalendarReminderSchedule.next(
                schedule, 60, OffsetDateTime.parse("2026-04-01T00:00:00Z"))).isNull();

        var everySixMonths = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2025-08-31T09:00:00+09:00"),
                "Asia/Seoul", MONTHLY, 6, LocalDate.parse("2027-08-31"));
        assertThat(CalendarReminderSchedule.next(
                everySixMonths, 0, OffsetDateTime.parse("2026-03-01T00:00:00Z")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-28T09:00:00+09:00"));
    }

    @Test
    void yearlyProjectionUsesLocalWallTimeAndSequentialLeapDayClamping() {
        var schedule = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2024-02-29T09:00:00-05:00"),
                "America/New_York", YEARLY, 1, LocalDate.parse("2030-03-01"));

        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2025-01-01T00:00:00Z")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2025-02-28T09:00:00-05:00"));
        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2026-01-01T00:00:00Z")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-02-28T09:00:00-05:00"));
        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2028-01-01T00:00:00Z")).startsAt())
                .isEqualTo(OffsetDateTime.parse("2028-02-28T09:00:00-05:00"));
    }

    @Test
    void oneOffPastOccurrenceExhaustsAndConfigurationBoundsFailClosed() {
        var schedule = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2026-01-01T09:00:00Z"),
                "UTC", NONE, 1, null);
        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2026-01-01T09:00:00.000000001Z")))
                .isNull();
        assertThatThrownBy(() -> CalendarReminderSchedule.next(
                schedule, 40321, OffsetDateTime.parse("2025-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weeklyByDayAndCountUseTheCanonicalSeriesOrdinal() {
        var schedule = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2026-01-05T09:00:00Z"),
                "UTC", WEEKLY, 2, null,
                List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 3);

        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2026-01-05T09:00:00.000000001Z"))
                .startsAt()).isEqualTo(OffsetDateTime.parse("2026-01-07T09:00:00Z"));
        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2026-01-07T09:00:00.000000001Z"))
                .startsAt()).isEqualTo(OffsetDateTime.parse("2026-01-19T09:00:00Z"));
        assertThat(CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2026-01-19T09:00:00.000000001Z")))
                .isNull();
    }

    @Test
    void untilAndCountTogetherFailClosed() {
        var schedule = new CalendarReminderSchedule.EventSchedule(
                OffsetDateTime.parse("2026-01-05T09:00:00Z"),
                "UTC", DAILY, 1, LocalDate.parse("2026-01-10"), List.of(), 3);

        assertThatThrownBy(() -> CalendarReminderSchedule.next(
                schedule, 0, OffsetDateTime.parse("2026-01-05T09:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }
}
