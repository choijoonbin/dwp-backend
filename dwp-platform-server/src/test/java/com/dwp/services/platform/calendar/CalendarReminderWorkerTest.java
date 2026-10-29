package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CalendarReminderWorkerTest {

    @Test
    void operationalBatchAndRetentionBoundsFailFastAtStartup() {
        CalendarReminderTransactions transactions = mock(CalendarReminderTransactions.class);
        CalendarReminderMetrics metrics = mock(CalendarReminderMetrics.class);
        assertThatThrownBy(() -> new CalendarReminderWorker(
                transactions, metrics, Clock.systemUTC(), true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarReminderWorker(
                transactions, metrics, Clock.systemUTC(), true, 501))
                .isInstanceOf(IllegalArgumentException.class);

        CalendarReminderRepository repository = mock(CalendarReminderRepository.class);
        assertThatThrownBy(() -> new CalendarReminderRetention(
                repository, metrics, Clock.systemUTC(), 0, 100, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarReminderRetention(
                repository, metrics, Clock.systemUTC(), 90, 1001, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarReminderRetention(
                repository, metrics, Clock.systemUTC(), 90, 100, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retentionDrainsFullBatchesForBoundedRoundsAndStopsOnPartialRound() {
        CalendarReminderRepository repository = mock(CalendarReminderRepository.class);
        CalendarReminderMetrics metrics = mock(CalendarReminderMetrics.class);
        when(repository.purgeDeliveryLedger(any(), eq(10))).thenReturn(10, 10, 4);
        when(repository.purgeInvalidSets(any(), eq(10))).thenReturn(10, 2, 0);
        CalendarReminderRetention retention = new CalendarReminderRetention(
                repository, metrics, Clock.systemUTC(), 90, 10, 5);

        retention.purge();

        verify(repository, times(3)).purgeDeliveryLedger(any(), eq(10));
        verify(repository, times(3)).purgeInvalidSets(any(), eq(10));
        verify(metrics).purged(36);
    }
}
