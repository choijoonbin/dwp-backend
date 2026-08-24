package com.dwp.services.platform.observability;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductSurfaceTelemetryMaintenanceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-24T12:30:00Z"), ZoneOffset.UTC);

    @Test
    void rollsUpBeforePurgingTheFixedRetentionWindows() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        OffsetDateTime rawCutoff = OffsetDateTime.parse("2026-07-25T12:30:00Z");
        LocalDate aggregateCutoff = LocalDate.parse("2026-02-25");
        when(repository.countRawBefore(rawCutoff)).thenReturn(1L, 0L);
        when(repository.countDailyBefore(aggregateCutoff)).thenReturn(1L, 0L);
        when(repository.purgeRaw(rawCutoff, 500)).thenReturn(1);
        when(repository.purgeDaily(aggregateCutoff, 500)).thenReturn(1);
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, true, 500);

        maintenance.rollUpAndPurge();

        verify(repository).rollUp(
                OffsetDateTime.parse("2026-07-25T00:00:00Z"),
                OffsetDateTime.parse("2026-08-24T00:00:00Z"));
        verify(repository, times(2)).countRawBefore(rawCutoff);
        verify(repository).purgeRaw(rawCutoff, 500);
        verify(repository, times(2)).countDailyBefore(aggregateCutoff);
        verify(repository).purgeDaily(aggregateCutoff, 500);
        assertThat(ProductSurfaceTelemetryMaintenance.RAW_RETENTION_DAYS).isEqualTo(30);
        assertThat(ProductSurfaceTelemetryMaintenance.DAILY_RETENTION_DAYS).isEqualTo(180);
    }

    @Test
    void drainsMoreThanTwentyFiveBatchesFromBothRetentionStores() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        int batchSize = 1000;
        long expiredRows = 25_001;
        AtomicLong rawRemaining = new AtomicLong(expiredRows);
        AtomicLong aggregateRemaining = new AtomicLong(expiredRows);
        when(repository.countRawBefore(any())).thenReturn(expiredRows, 0L);
        when(repository.countDailyBefore(any())).thenReturn(expiredRows, 0L);
        when(repository.purgeRaw(any(), eq(batchSize)))
                .thenAnswer(ignored -> removeBatch(rawRemaining, batchSize));
        when(repository.purgeDaily(any(), eq(batchSize)))
                .thenAnswer(ignored -> removeBatch(aggregateRemaining, batchSize));
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, true, batchSize);

        maintenance.rollUpAndPurge();

        assertThat(rawRemaining).hasValue(0);
        assertThat(aggregateRemaining).hasValue(0);
        verify(repository, times(26)).purgeRaw(any(), eq(batchSize));
        verify(repository, times(26)).purgeDaily(any(), eq(batchSize));
    }

    @Test
    void failsVisiblyWhenLockedRawRowsPreventBacklogDrain() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        when(repository.countRawBefore(any())).thenReturn(600L, 100L);
        when(repository.purgeRaw(any(), anyInt())).thenReturn(500, 0);
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, true, 500);

        assertThatThrownBy(maintenance::rollUpAndPurge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw retention failed")
                .hasMessageContaining("initial=600")
                .hasMessageContaining("removed=500")
                .hasMessageContaining("remaining=100")
                .hasMessageContaining("locked");
        verify(repository, never()).countDailyBefore(any());
    }

    @Test
    void failsVisiblyWhenLockedAggregateRowsPreventBacklogDrain() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        when(repository.countRawBefore(any())).thenReturn(0L);
        when(repository.countDailyBefore(any())).thenReturn(41L, 41L);
        when(repository.purgeDaily(any(), anyInt())).thenReturn(0);
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, true, 500);

        assertThatThrownBy(maintenance::rollUpAndPurge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregate retention failed")
                .hasMessageContaining("initial=41")
                .hasMessageContaining("removed=0")
                .hasMessageContaining("remaining=41");
    }

    @Test
    void failsInsteadOfLoopingWhenExpiredBacklogGrowsDuringDrain() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        when(repository.countRawBefore(any())).thenReturn(1L, 1L);
        when(repository.purgeRaw(any(), anyInt())).thenReturn(1);
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, true, 500);

        assertThatThrownBy(maintenance::rollUpAndPurge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw retention failed")
                .hasMessageContaining("expired-row ingestion may be occurring");
        verify(repository, times(1)).purgeRaw(any(), anyInt());
    }

    @Test
    void failsWhenPurgeProgressExceedsTheBoundedBacklogSnapshot() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        when(repository.countRawBefore(any())).thenReturn(1L);
        when(repository.purgeRaw(any(), anyInt())).thenReturn(2);
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, true, 500);

        assertThatThrownBy(maintenance::rollUpAndPurge)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw retention failed")
                .hasMessageContaining("invalid purge batch size of 2");
        verify(repository, times(1)).purgeRaw(any(), anyInt());
    }

    @Test
    void doesNothingWhenMaintenanceIsDisabled() {
        ProductSurfaceTelemetryRepository repository =
                mock(ProductSurfaceTelemetryRepository.class);
        ProductSurfaceTelemetryMaintenance maintenance =
                new ProductSurfaceTelemetryMaintenance(repository, CLOCK, false, 500);

        maintenance.rollUpAndPurge();

        verifyNoInteractions(repository);
    }

    private static int removeBatch(AtomicLong remaining, int batchSize) {
        long removed = Math.min(remaining.get(), batchSize);
        remaining.addAndGet(-removed);
        return Math.toIntExact(removed);
    }
}
