package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarConflictSweepTest {

    @Test
    void denseTenThousandIntervalsAreAllDetectedWithLinearHighLevelOperations() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-01T09:00:00Z");
        List<CalendarConflictSweep.Interval<Integer>> values = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            values.add(new CalendarConflictSweep.Interval<>(
                    index, new UUID(0, index + 1L), startsAt, startsAt.plusHours(1), true));
        }

        CalendarConflictSweep.Result<Integer> result = CalendarConflictSweep.conflicts(values);

        assertThat(result.conflicts()).hasSize(values.size());
        assertThat(result.operationCount()).isLessThanOrEqualTo(values.size() * 12L);
    }

    @Test
    void oneHundredThousandNonOverlappingIntervalsStayConflictFreeWithinBudget() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        List<CalendarConflictSweep.Interval<Integer>> values = new ArrayList<>();
        for (int index = 0; index < 100_000; index++) {
            OffsetDateTime start = startsAt.plusMinutes(index * 2L);
            values.add(new CalendarConflictSweep.Interval<>(
                    index, new UUID(0, index % 50L + 1L),
                    start, start.plusMinutes(1), true));
        }

        CalendarConflictSweep.Result<Integer> result = CalendarConflictSweep.conflicts(values);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.operationCount()).isLessThanOrEqualTo(values.size() * 12L);
    }

    @Test
    void overlappingOccurrencesOfTheSameEventDoNotConflictWithEachOther() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-10-01T09:00:00Z");
        UUID eventId = UUID.randomUUID();

        CalendarConflictSweep.Result<Integer> result = CalendarConflictSweep.conflicts(List.of(
                new CalendarConflictSweep.Interval<>(
                        1, eventId, startsAt, startsAt.plusHours(2), true),
                new CalendarConflictSweep.Interval<>(
                        2, eventId, startsAt.plusMinutes(30), startsAt.plusHours(3), true)));

        assertThat(result.conflicts()).isEmpty();
    }
}
