package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarSchedulingHorizonTest {

    @Test
    void horizonUsesCanonicalLocalDatesAcrossDaylightSavingTransitions() {
        CalendarSchedulingHorizon evaluator = new CalendarSchedulingHorizon(Clock.fixed(
                Instant.parse("2026-10-25T13:00:00Z"), ZoneOffset.UTC));
        ZoneId zone = ZoneId.of("America/New_York");

        CalendarSchedulingHorizon.Horizon horizon = evaluator.evaluate(zone, 14);

        assertThat(horizon.latestDate()).isEqualTo(LocalDate.parse("2026-11-08"));
        assertThat(horizon.contains(
                OffsetDateTime.parse("2026-11-08T23:30:00-05:00"), zone)).isTrue();
        assertThat(horizon.contains(
                OffsetDateTime.parse("2026-11-09T00:00:00-05:00"), zone)).isFalse();
    }
}
