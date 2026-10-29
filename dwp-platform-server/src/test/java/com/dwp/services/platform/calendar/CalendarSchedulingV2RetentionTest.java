package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarSchedulingV2RetentionTest {

    @Test
    void drainsBacklogInBoundedBatchesAndStopsOnPartialBatch() {
        CalendarSchedulingV2Repository repository = mock(CalendarSchedulingV2Repository.class);
        when(repository.purgeExpiredGlobal(any(OffsetDateTime.class), eq(1000)))
                .thenReturn(1000, 1000, 137);
        CalendarSchedulingV2Retention retention = new CalendarSchedulingV2Retention(
                repository,
                Clock.fixed(Instant.parse("2026-11-02T14:00:00Z"), ZoneOffset.UTC),
                300, 1000, 20);

        assertThat(retention.purgeExpired()).isEqualTo(2137);
        verify(repository, org.mockito.Mockito.times(3))
                .purgeExpiredGlobal(
                        OffsetDateTime.parse("2026-11-02T13:55:00Z"), 1000);
    }

    @Test
    void capsWorkPerRunWhenBacklogRemains() {
        CalendarSchedulingV2Repository repository = mock(CalendarSchedulingV2Repository.class);
        when(repository.purgeExpiredGlobal(any(OffsetDateTime.class), eq(10))).thenReturn(10);
        CalendarSchedulingV2Retention retention = new CalendarSchedulingV2Retention(
                repository, Clock.systemUTC(), 300, 10, 3);

        assertThat(retention.purgeExpired()).isEqualTo(30);
        verify(repository, org.mockito.Mockito.times(3))
                .purgeExpiredGlobal(any(OffsetDateTime.class), eq(10));
    }
}
